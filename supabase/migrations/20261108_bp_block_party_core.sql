-- Block Party (shared street tycoon) backend.
-- Target: Supabase project "not-a-book-club". Every object is prefixed bp_ so it stays
-- separate from the other app in the same project. Safe to run once in the SQL editor.

create table public.bp_streets (
  id uuid primary key default gen_random_uuid(),
  name text not null check (char_length(name) between 1 and 60),
  admin_id uuid not null references auth.users(id) on delete cascade,
  invite_code text not null unique,
  time_zone text not null default 'UTC',
  level int not null default 0,
  created_at timestamptz not null default now()
);

create table public.bp_members (
  street_id uuid not null references public.bp_streets(id) on delete cascade,
  user_id uuid not null references auth.users(id) on delete cascade,
  display_name text not null default '' check (char_length(display_name) <= 40),
  joined_at timestamptz not null default now(),
  primary key (street_id, user_id)
);
create index bp_members_user_idx on public.bp_members(user_id);

-- Everything is separate per street: each player's money, books, storage and level live here.
create table public.bp_player_state (
  street_id uuid not null,
  user_id uuid not null,
  state jsonb not null default '{}'::jsonb,
  updated_at timestamptz not null default now(),
  primary key (street_id, user_id),
  foreign key (street_id, user_id) references public.bp_members(street_id, user_id) on delete cascade
);

create table public.bp_lots (
  street_id uuid not null references public.bp_streets(id) on delete cascade,
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
create index bp_lots_owner_idx on public.bp_lots(owner_id);

-- Player drawings shared with the street (the PNGs live in the bp-sprites storage bucket).
create table public.bp_sprites (
  street_id uuid not null references public.bp_streets(id) on delete cascade,
  id text not null,
  owner_id uuid not null references auth.users(id) on delete cascade,
  meta jsonb not null,
  created_at timestamptz not null default now(),
  primary key (street_id, id)
);
create index bp_sprites_owner_idx on public.bp_sprites(owner_id);

-- Membership helpers (security definer so policies don't recurse).
create function public.bp_is_member(p_street uuid) returns boolean
language sql stable security definer set search_path = '' as $$
  select exists (select 1 from public.bp_members m where m.street_id = p_street and m.user_id = (select auth.uid()));
$$;

create function public.bp_is_admin(p_street uuid) returns boolean
language sql stable security definer set search_path = '' as $$
  select exists (select 1 from public.bp_streets s where s.id = p_street and s.admin_id = (select auth.uid()));
$$;

alter table public.bp_streets enable row level security;
alter table public.bp_members enable row level security;
alter table public.bp_player_state enable row level security;
alter table public.bp_lots enable row level security;
alter table public.bp_sprites enable row level security;

create policy "members read their streets" on public.bp_streets for select to authenticated using (public.bp_is_member(id));
create policy "admin renames street" on public.bp_streets for update to authenticated using (admin_id = (select auth.uid())) with check (admin_id = (select auth.uid()));

create policy "members see street members" on public.bp_members for select to authenticated using (public.bp_is_member(street_id));
create policy "leave a street, or admin removes" on public.bp_members for delete to authenticated
  using (user_id = (select auth.uid()) or public.bp_is_admin(street_id));
create policy "update own display name" on public.bp_members for update to authenticated
  using (user_id = (select auth.uid())) with check (user_id = (select auth.uid()));

create policy "own state only" on public.bp_player_state for all to authenticated
  using (user_id = (select auth.uid())) with check (user_id = (select auth.uid()) and public.bp_is_member(street_id));

create policy "members see lots" on public.bp_lots for select to authenticated using (public.bp_is_member(street_id));
-- Owners edit their shop's contents; claiming, closing and merging go through functions below.
create policy "owners edit their shop" on public.bp_lots for update to authenticated
  using (owner_id = (select auth.uid()) or public.bp_is_admin(street_id))
  with check (owner_id = (select auth.uid()) or public.bp_is_admin(street_id));
revoke update on public.bp_lots from authenticated;
grant update (business, furniture, owner_name, updated_at) on public.bp_lots to authenticated;

create policy "members see drawings" on public.bp_sprites for select to authenticated using (public.bp_is_member(street_id));
create policy "members add their drawings" on public.bp_sprites for insert to authenticated
  with check (owner_id = (select auth.uid()) and public.bp_is_member(street_id));
create policy "artist edits own drawing" on public.bp_sprites for update to authenticated
  using (owner_id = (select auth.uid())) with check (owner_id = (select auth.uid()));
create policy "artist or admin removes drawing" on public.bp_sprites for delete to authenticated
  using (owner_id = (select auth.uid()) or public.bp_is_admin(street_id));

-- Lot layout: lots are 6x6 in a row with a one-tile gap.
create function public.bp_lot_rect(p_index int) returns jsonb
language sql immutable set search_path = '' as $$
  select jsonb_build_object('x', 1 + p_index * 7, 'y', 0, 'w', 6, 'h', 6);
$$;

create function public.bp_new_invite_code() returns text
language sql volatile set search_path = '' as $$
  select upper(substr(md5(gen_random_uuid()::text), 1, 6));
$$;

-- Keeps at least one vacant lot so the street grows as people join and open shops.
create function public.bp_ensure_vacant_lot(p_street uuid) returns void
language plpgsql security definer set search_path = '' as $$
declare next_x int;
begin
  if not exists (select 1 from public.bp_lots where street_id = p_street and state = 'VACANT') then
    select coalesce(max((rect->>'x')::int + (rect->>'w')::int), 0) + 1 into next_x from public.bp_lots where street_id = p_street;
    insert into public.bp_lots (street_id, lot_id, rect, state)
    values (p_street, 'lot-' || substr(gen_random_uuid()::text, 1, 8),
            jsonb_build_object('x', next_x, 'y', 0, 'w', 6, 'h', 6), 'VACANT');
  end if;
end;
$$;

create function public.bp_create_street(p_name text, p_time_zone text, p_display_name text) returns uuid
language plpgsql security definer set search_path = '' as $$
declare sid uuid; code text; uid uuid := (select auth.uid());
begin
  if uid is null then raise exception 'Sign in first'; end if;
  loop
    code := public.bp_new_invite_code();
    exit when not exists (select 1 from public.bp_streets where invite_code = code);
  end loop;
  insert into public.bp_streets (name, admin_id, invite_code, time_zone)
  values (left(p_name, 60), uid, code, coalesce(nullif(p_time_zone, ''), 'UTC')) returning id into sid;
  insert into public.bp_members (street_id, user_id, display_name) values (sid, uid, left(coalesce(p_display_name, ''), 40));
  insert into public.bp_player_state (street_id, user_id) values (sid, uid);
  insert into public.bp_lots (street_id, lot_id, rect, state)
  select sid, 'lot-' || i, public.bp_lot_rect(i), 'VACANT' from generate_series(0, 2) as i;
  return sid;
end;
$$;

create function public.bp_join_street(p_code text, p_display_name text) returns uuid
language plpgsql security definer set search_path = '' as $$
declare sid uuid; uid uuid := (select auth.uid());
begin
  if uid is null then raise exception 'Sign in first'; end if;
  select id into sid from public.bp_streets where invite_code = upper(trim(p_code));
  if sid is null then raise exception 'No street with that invite code'; end if;
  insert into public.bp_members (street_id, user_id, display_name)
  values (sid, uid, left(coalesce(p_display_name, ''), 40)) on conflict do nothing;
  insert into public.bp_player_state (street_id, user_id) values (sid, uid) on conflict do nothing;
  perform public.bp_ensure_vacant_lot(sid);
  return sid;
end;
$$;

-- Claim a vacant lot (the client checks and deducts the price from the player's own coins).
create function public.bp_claim_lot(p_street uuid, p_lot text, p_owner_name text, p_business jsonb) returns boolean
language plpgsql security definer set search_path = '' as $$
declare uid uuid := (select auth.uid()); n int;
begin
  if not public.bp_is_member(p_street) then raise exception 'Not a member of this street'; end if;
  update public.bp_lots set state = 'OWNED', owner_id = uid, owner_name = left(coalesce(p_owner_name, ''), 40),
    business = p_business, furniture = '[]'::jsonb, updated_at = now()
  where street_id = p_street and lot_id = p_lot and state = 'VACANT';
  get diagnostics n = row_count;
  perform public.bp_ensure_vacant_lot(p_street);
  return n = 1;
end;
$$;

create function public.bp_close_lot(p_street uuid, p_lot text) returns boolean
language plpgsql security definer set search_path = '' as $$
declare n int;
begin
  update public.bp_lots set state = 'VACANT', owner_id = null, owner_name = '', business = null,
    furniture = '[]'::jsonb, updated_at = now()
  where street_id = p_street and lot_id = p_lot and (owner_id = (select auth.uid()) or public.bp_is_admin(p_street));
  get diagnostics n = row_count;
  return n = 1;
end;
$$;

-- Merge a shop with the neighbouring lot: the client sends the combined rect, business and furniture.
create function public.bp_merge_lots(p_street uuid, p_keep text, p_other text, p_rect jsonb, p_business jsonb, p_furniture jsonb)
returns boolean language plpgsql security definer set search_path = '' as $$
declare uid uuid := (select auth.uid());
begin
  if not exists (select 1 from public.bp_lots where street_id = p_street and lot_id = p_keep and owner_id = uid) then
    raise exception 'You can only expand your own shop';
  end if;
  if not exists (select 1 from public.bp_lots where street_id = p_street and lot_id = p_other
                 and (state = 'VACANT' or owner_id = uid)) then
    raise exception 'That lot is not available';
  end if;
  delete from public.bp_lots where street_id = p_street and lot_id = p_other;
  update public.bp_lots set rect = p_rect, business = p_business, furniture = p_furniture, updated_at = now()
  where street_id = p_street and lot_id = p_keep;
  perform public.bp_ensure_vacant_lot(p_street);
  return true;
end;
$$;

create function public.bp_new_invite(p_street uuid) returns text
language plpgsql security definer set search_path = '' as $$
declare code text;
begin
  if not public.bp_is_admin(p_street) then raise exception 'Only the street admin can do that'; end if;
  loop
    code := public.bp_new_invite_code();
    exit when not exists (select 1 from public.bp_streets where invite_code = code);
  end loop;
  update public.bp_streets set invite_code = code where id = p_street;
  return code;
end;
$$;

-- Admin moderation: remove a member (their shops become vacant).
create function public.bp_remove_member(p_street uuid, p_user uuid) returns void
language plpgsql security definer set search_path = '' as $$
begin
  if not public.bp_is_admin(p_street) then raise exception 'Only the street admin can do that'; end if;
  if p_user = (select auth.uid()) then raise exception 'The admin cannot remove themselves'; end if;
  update public.bp_lots set state = 'VACANT', owner_id = null, owner_name = '', business = null, furniture = '[]'::jsonb
  where street_id = p_street and owner_id = p_user;
  delete from public.bp_members where street_id = p_street and user_id = p_user;
end;
$$;

revoke execute on function public.bp_ensure_vacant_lot(uuid) from public, anon, authenticated;
revoke execute on function public.bp_create_street(text, text, text) from public, anon;
revoke execute on function public.bp_join_street(text, text) from public, anon;
revoke execute on function public.bp_claim_lot(uuid, text, text, jsonb) from public, anon;
revoke execute on function public.bp_close_lot(uuid, text) from public, anon;
revoke execute on function public.bp_merge_lots(uuid, text, text, jsonb, jsonb, jsonb) from public, anon;
revoke execute on function public.bp_new_invite(uuid) from public, anon;
revoke execute on function public.bp_remove_member(uuid, uuid) from public, anon;
grant execute on function public.bp_create_street(text, text, text) to authenticated;
grant execute on function public.bp_join_street(text, text) to authenticated;
grant execute on function public.bp_claim_lot(uuid, text, text, jsonb) to authenticated;
grant execute on function public.bp_close_lot(uuid, text) to authenticated;
grant execute on function public.bp_merge_lots(uuid, text, text, jsonb, jsonb, jsonb) to authenticated;
grant execute on function public.bp_new_invite(uuid) to authenticated;
grant execute on function public.bp_remove_member(uuid, uuid) to authenticated;

-- Drawings bucket: private; members of a street read and add files under <street_id>/.
insert into storage.buckets (id, name, public, file_size_limit, allowed_mime_types)
values ('bp-sprites', 'bp-sprites', false, 2097152, array['image/png'])
on conflict (id) do nothing;

create policy "bp members read street drawings" on storage.objects for select to authenticated
  using (bucket_id = 'bp-sprites' and public.bp_is_member(((storage.foldername(name))[1])::uuid));
create policy "bp members upload street drawings" on storage.objects for insert to authenticated
  with check (bucket_id = 'bp-sprites' and public.bp_is_member(((storage.foldername(name))[1])::uuid));
create policy "bp uploader or admin deletes drawings" on storage.objects for delete to authenticated
  using (bucket_id = 'bp-sprites' and (owner_id = (select auth.uid())::text
         or public.bp_is_admin(((storage.foldername(name))[1])::uuid)));

-- Live updates for street changes.
alter publication supabase_realtime add table public.bp_lots, public.bp_sprites, public.bp_members;
