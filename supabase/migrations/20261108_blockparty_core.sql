-- Block Party (shared street tycoon) backend, in its own "blockparty" schema so it stays
-- separate from other apps sharing this Supabase project.
--
-- After running this, expose the schema to the API once in the dashboard:
--   Project Settings -> Data API -> Exposed schemas -> add "blockparty".

create schema if not exists blockparty;

grant usage on schema blockparty to anon, authenticated, service_role;

create table blockparty.streets (
  id uuid primary key default gen_random_uuid(),
  name text not null check (char_length(name) between 1 and 60),
  admin_id uuid not null references auth.users(id) on delete cascade,
  invite_code text not null unique,
  time_zone text not null default 'UTC',
  level int not null default 0,
  created_at timestamptz not null default now()
);

create table blockparty.members (
  street_id uuid not null references blockparty.streets(id) on delete cascade,
  user_id uuid not null references auth.users(id) on delete cascade,
  display_name text not null default '' check (char_length(display_name) <= 40),
  joined_at timestamptz not null default now(),
  primary key (street_id, user_id)
);
create index members_user_idx on blockparty.members(user_id);

-- Everything is separate per street: each player's money, books, storage and level live here.
create table blockparty.player_state (
  street_id uuid not null,
  user_id uuid not null,
  state jsonb not null default '{}'::jsonb,
  updated_at timestamptz not null default now(),
  primary key (street_id, user_id),
  foreign key (street_id, user_id) references blockparty.members(street_id, user_id) on delete cascade
);

create table blockparty.lots (
  street_id uuid not null references blockparty.streets(id) on delete cascade,
  lot_id text not null,
  rect jsonb not null,
  state text not null check (state in ('VACANT', 'OWNED', 'LOCKED')),
  owner_id uuid references auth.users(id) on delete set null,
  owner_name text not null default '',
  business jsonb,
  furniture jsonb not null default '[]'::jsonb,
  updated_at timestamptz not null default now(),
  primary key (street_id, lot_id)
);
create index lots_owner_idx on blockparty.lots(owner_id);

-- Player drawings shared with the street (the PNGs live in the blockparty-drawings bucket).
create table blockparty.sprites (
  street_id uuid not null references blockparty.streets(id) on delete cascade,
  id text not null,
  owner_id uuid not null references auth.users(id) on delete cascade,
  meta jsonb not null,
  created_at timestamptz not null default now(),
  primary key (street_id, id)
);
create index sprites_owner_idx on blockparty.sprites(owner_id);

-- Table privileges (custom schemas don't get Supabase's default grants). Row-level security
-- below decides which rows each signed-in player can actually touch; anon gets nothing.
grant select, insert, update, delete on all tables in schema blockparty to authenticated;
grant all on all tables in schema blockparty to service_role;
revoke update on blockparty.lots from authenticated;
grant update (business, furniture, owner_name, updated_at) on blockparty.lots to authenticated;
revoke insert, delete on blockparty.lots from authenticated;
revoke insert on blockparty.streets, blockparty.members from authenticated;

-- Membership helpers (security definer so policies don't recurse).
create function blockparty.is_member(p_street uuid) returns boolean
language sql stable security definer set search_path = '' as $$
  select exists (select 1 from blockparty.members m where m.street_id = p_street and m.user_id = (select auth.uid()));
$$;

create function blockparty.is_admin(p_street uuid) returns boolean
language sql stable security definer set search_path = '' as $$
  select exists (select 1 from blockparty.streets s where s.id = p_street and s.admin_id = (select auth.uid()));
$$;

alter table blockparty.streets enable row level security;
alter table blockparty.members enable row level security;
alter table blockparty.player_state enable row level security;
alter table blockparty.lots enable row level security;
alter table blockparty.sprites enable row level security;

create policy "members read their streets" on blockparty.streets for select to authenticated
  using (blockparty.is_member(id));
create policy "admin renames street" on blockparty.streets for update to authenticated
  using (admin_id = (select auth.uid())) with check (admin_id = (select auth.uid()));

create policy "members see street members" on blockparty.members for select to authenticated
  using (blockparty.is_member(street_id));
create policy "leave a street, or admin removes" on blockparty.members for delete to authenticated
  using (user_id = (select auth.uid()) or blockparty.is_admin(street_id));
create policy "update own display name" on blockparty.members for update to authenticated
  using (user_id = (select auth.uid())) with check (user_id = (select auth.uid()));

create policy "own state only" on blockparty.player_state for all to authenticated
  using (user_id = (select auth.uid()))
  with check (user_id = (select auth.uid()) and blockparty.is_member(street_id));

create policy "members see lots" on blockparty.lots for select to authenticated
  using (blockparty.is_member(street_id));
-- Owners edit their shop's contents; claiming, closing and merging go through functions below.
create policy "owners edit their shop" on blockparty.lots for update to authenticated
  using (owner_id = (select auth.uid()) or blockparty.is_admin(street_id))
  with check (owner_id = (select auth.uid()) or blockparty.is_admin(street_id));

create policy "members see drawings" on blockparty.sprites for select to authenticated
  using (blockparty.is_member(street_id));
create policy "members add their drawings" on blockparty.sprites for insert to authenticated
  with check (owner_id = (select auth.uid()) and blockparty.is_member(street_id));
create policy "artist edits own drawing" on blockparty.sprites for update to authenticated
  using (owner_id = (select auth.uid())) with check (owner_id = (select auth.uid()));
create policy "artist or admin removes drawing" on blockparty.sprites for delete to authenticated
  using (owner_id = (select auth.uid()) or blockparty.is_admin(street_id));

-- Lot layout: lots are 6x6 in a row with a one-tile gap.
create function blockparty.lot_rect(p_index int) returns jsonb
language sql immutable set search_path = '' as $$
  select jsonb_build_object('x', 1 + p_index * 7, 'y', 0, 'w', 6, 'h', 6);
$$;

create function blockparty.new_invite_code() returns text
language sql volatile set search_path = '' as $$
  select upper(substr(md5(gen_random_uuid()::text), 1, 6));
$$;

-- Keeps at least one vacant lot so the street grows as people join and open shops.
create function blockparty.ensure_vacant_lot(p_street uuid) returns void
language plpgsql security definer set search_path = '' as $$
declare next_x int;
begin
  if not exists (select 1 from blockparty.lots where street_id = p_street and state = 'VACANT') then
    select coalesce(max((rect->>'x')::int + (rect->>'w')::int), 0) + 1 into next_x
    from blockparty.lots where street_id = p_street;
    insert into blockparty.lots (street_id, lot_id, rect, state)
    values (p_street, 'lot-' || substr(gen_random_uuid()::text, 1, 8),
            jsonb_build_object('x', next_x, 'y', 0, 'w', 6, 'h', 6), 'VACANT');
  end if;
end;
$$;

create function blockparty.create_street(p_name text, p_time_zone text, p_display_name text) returns uuid
language plpgsql security definer set search_path = '' as $$
declare sid uuid; code text; uid uuid := (select auth.uid());
begin
  if uid is null then raise exception 'Sign in first'; end if;
  loop
    code := blockparty.new_invite_code();
    exit when not exists (select 1 from blockparty.streets where invite_code = code);
  end loop;
  insert into blockparty.streets (name, admin_id, invite_code, time_zone)
  values (left(p_name, 60), uid, code, coalesce(nullif(p_time_zone, ''), 'UTC')) returning id into sid;
  insert into blockparty.members (street_id, user_id, display_name)
  values (sid, uid, left(coalesce(p_display_name, ''), 40));
  insert into blockparty.player_state (street_id, user_id) values (sid, uid);
  insert into blockparty.lots (street_id, lot_id, rect, state)
  select sid, 'lot-' || i, blockparty.lot_rect(i), 'VACANT' from generate_series(0, 2) as i;
  return sid;
end;
$$;

create function blockparty.join_street(p_code text, p_display_name text) returns uuid
language plpgsql security definer set search_path = '' as $$
declare sid uuid; uid uuid := (select auth.uid());
begin
  if uid is null then raise exception 'Sign in first'; end if;
  select id into sid from blockparty.streets where invite_code = upper(trim(p_code));
  if sid is null then raise exception 'No street with that invite code'; end if;
  insert into blockparty.members (street_id, user_id, display_name)
  values (sid, uid, left(coalesce(p_display_name, ''), 40)) on conflict do nothing;
  insert into blockparty.player_state (street_id, user_id) values (sid, uid) on conflict do nothing;
  perform blockparty.ensure_vacant_lot(sid);
  return sid;
end;
$$;

-- Claim a vacant lot (the client checks and deducts the price from the player's own coins).
create function blockparty.claim_lot(p_street uuid, p_lot text, p_owner_name text, p_business jsonb) returns boolean
language plpgsql security definer set search_path = '' as $$
declare uid uuid := (select auth.uid()); n int;
begin
  if not blockparty.is_member(p_street) then raise exception 'Not a member of this street'; end if;
  update blockparty.lots set state = 'OWNED', owner_id = uid, owner_name = left(coalesce(p_owner_name, ''), 40),
    business = p_business, furniture = '[]'::jsonb, updated_at = now()
  where street_id = p_street and lot_id = p_lot and state = 'VACANT';
  get diagnostics n = row_count;
  perform blockparty.ensure_vacant_lot(p_street);
  return n = 1;
end;
$$;

create function blockparty.close_lot(p_street uuid, p_lot text) returns boolean
language plpgsql security definer set search_path = '' as $$
declare n int;
begin
  update blockparty.lots set state = 'VACANT', owner_id = null, owner_name = '', business = null,
    furniture = '[]'::jsonb, updated_at = now()
  where street_id = p_street and lot_id = p_lot
    and (owner_id = (select auth.uid()) or blockparty.is_admin(p_street));
  get diagnostics n = row_count;
  return n = 1;
end;
$$;

-- Merge a shop with the neighbouring lot: the client sends the combined rect, business and furniture.
create function blockparty.merge_lots(p_street uuid, p_keep text, p_other text, p_rect jsonb, p_business jsonb, p_furniture jsonb)
returns boolean language plpgsql security definer set search_path = '' as $$
declare uid uuid := (select auth.uid());
begin
  if not exists (select 1 from blockparty.lots where street_id = p_street and lot_id = p_keep and owner_id = uid) then
    raise exception 'You can only expand your own shop';
  end if;
  if not exists (select 1 from blockparty.lots where street_id = p_street and lot_id = p_other
                 and (state = 'VACANT' or owner_id = uid)) then
    raise exception 'That lot is not available';
  end if;
  delete from blockparty.lots where street_id = p_street and lot_id = p_other;
  update blockparty.lots set rect = p_rect, business = p_business, furniture = p_furniture, updated_at = now()
  where street_id = p_street and lot_id = p_keep;
  perform blockparty.ensure_vacant_lot(p_street);
  return true;
end;
$$;

create function blockparty.new_invite(p_street uuid) returns text
language plpgsql security definer set search_path = '' as $$
declare code text;
begin
  if not blockparty.is_admin(p_street) then raise exception 'Only the street admin can do that'; end if;
  loop
    code := blockparty.new_invite_code();
    exit when not exists (select 1 from blockparty.streets where invite_code = code);
  end loop;
  update blockparty.streets set invite_code = code where id = p_street;
  return code;
end;
$$;

-- Admin moderation: remove a member (their shops become vacant).
create function blockparty.remove_member(p_street uuid, p_user uuid) returns void
language plpgsql security definer set search_path = '' as $$
begin
  if not blockparty.is_admin(p_street) then raise exception 'Only the street admin can do that'; end if;
  if p_user = (select auth.uid()) then raise exception 'The admin cannot remove themselves'; end if;
  update blockparty.lots set state = 'VACANT', owner_id = null, owner_name = '', business = null, furniture = '[]'::jsonb
  where street_id = p_street and owner_id = p_user;
  delete from blockparty.members where street_id = p_street and user_id = p_user;
end;
$$;

-- Only signed-in players can call the game's functions; internal helpers aren't callable at all.
revoke execute on all functions in schema blockparty from public, anon;
revoke execute on function blockparty.ensure_vacant_lot(uuid) from authenticated;
grant execute on function blockparty.create_street(text, text, text) to authenticated;
grant execute on function blockparty.join_street(text, text) to authenticated;
grant execute on function blockparty.claim_lot(uuid, text, text, jsonb) to authenticated;
grant execute on function blockparty.close_lot(uuid, text) to authenticated;
grant execute on function blockparty.merge_lots(uuid, text, text, jsonb, jsonb, jsonb) to authenticated;
grant execute on function blockparty.new_invite(uuid) to authenticated;
grant execute on function blockparty.remove_member(uuid, uuid) to authenticated;
grant execute on function blockparty.is_member(uuid) to authenticated;
grant execute on function blockparty.is_admin(uuid) to authenticated;
grant execute on all functions in schema blockparty to service_role;

-- Drawings bucket: private; members of a street read and add files under <street_id>/.
insert into storage.buckets (id, name, public, file_size_limit, allowed_mime_types)
values ('blockparty-drawings', 'blockparty-drawings', false, 2097152, array['image/png'])
on conflict (id) do nothing;

create policy "blockparty members read street drawings" on storage.objects for select to authenticated
  using (bucket_id = 'blockparty-drawings' and blockparty.is_member(((storage.foldername(name))[1])::uuid));
create policy "blockparty members upload street drawings" on storage.objects for insert to authenticated
  with check (bucket_id = 'blockparty-drawings' and blockparty.is_member(((storage.foldername(name))[1])::uuid));
create policy "blockparty uploader or admin deletes drawings" on storage.objects for delete to authenticated
  using (bucket_id = 'blockparty-drawings' and (owner_id = (select auth.uid())::text
         or blockparty.is_admin(((storage.foldername(name))[1])::uuid)));

-- Live updates for street changes.
alter publication supabase_realtime add table blockparty.lots, blockparty.sprites, blockparty.members;
