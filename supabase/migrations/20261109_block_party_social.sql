-- Block Party stage 8 (first part): street chat, notes at shop doors, and money between players
-- (gifts, loans and repayments). Run after 20261108_block_party_core.sql.

-- Street chat. Messages stay when their author leaves (the name is kept with the message).
create table block_party.messages (
  id uuid primary key default gen_random_uuid(),
  street_id uuid not null references block_party.streets(id) on delete cascade,
  author_id uuid references public.profiles(id) on delete set null,
  author_name text not null default '' check (char_length(author_name) <= 40),
  body text not null check (char_length(body) between 1 and 500),
  created_at timestamptz not null default now()
);
create index messages_street_time_idx on block_party.messages(street_id, created_at);
create index messages_author_idx on block_party.messages(author_id);

-- Notes left at a shop's door. They go when the lot does (merged away).
create table block_party.door_notes (
  id uuid primary key default gen_random_uuid(),
  street_id uuid not null,
  lot_id text not null,
  author_id uuid references public.profiles(id) on delete set null,
  author_name text not null default '' check (char_length(author_name) <= 40),
  body text not null check (char_length(body) between 1 and 280),
  created_at timestamptz not null default now(),
  foreign key (street_id, lot_id) references block_party.lots(street_id, lot_id) on delete cascade
);
create index door_notes_lot_idx on block_party.door_notes(street_id, lot_id, created_at);
create index door_notes_author_idx on block_party.door_notes(author_id);

-- Money between players. Coins live on each player's device, so a transfer is a hand-off: the
-- sender's device records it and takes the coins off; the recipient's device collects it with
-- claim_transfer, which marks it claimed in the same step so it can only be paid out once.
-- A loan is money lent; repayments point at the loan they pay back.
create table block_party.transfers (
  id uuid primary key default gen_random_uuid(),
  street_id uuid not null references block_party.streets(id) on delete cascade,
  from_id uuid references public.profiles(id) on delete set null,
  to_id uuid references public.profiles(id) on delete set null,
  from_name text not null default '' check (char_length(from_name) <= 40),
  kind text not null check (kind in ('gift', 'loan', 'repayment')),
  amount bigint not null check (amount between 1 and 1000000000),
  note text not null default '' check (char_length(note) <= 140),
  loan_id uuid references block_party.transfers(id) on delete set null,
  created_at timestamptz not null default now(),
  claimed_at timestamptz,
  check (from_id is distinct from to_id),
  check ((kind = 'repayment') = (loan_id is not null))
);
create index transfers_to_idx on block_party.transfers(street_id, to_id, claimed_at);
create index transfers_from_idx on block_party.transfers(street_id, from_id);
create index transfers_to_id_idx on block_party.transfers(to_id);
create index transfers_from_id_idx on block_party.transfers(from_id);
create index transfers_loan_idx on block_party.transfers(loan_id);

-- Is this user on the street? (Like is_member, for someone other than the caller.)
create function block_party.has_member(p_street uuid, p_user uuid) returns boolean
language sql stable security definer set search_path = '' as $$
  select exists (select 1 from block_party.members m where m.street_id = p_street and m.user_id = p_user);
$$;

-- A repayment must pay back a loan the payer received, on the same street, to the lender.
create function block_party.repays_own_loan(p_loan uuid, p_street uuid, p_payer uuid, p_payee uuid) returns boolean
language sql stable security definer set search_path = '' as $$
  select exists (
    select 1 from block_party.transfers t
    where t.id = p_loan and t.kind = 'loan' and t.street_id = p_street and t.to_id = p_payer and t.from_id = p_payee
  );
$$;

-- Collects money sent to the caller. Returns the amount, or null if it was already collected
-- (or isn't theirs).
create function block_party.claim_transfer(p_id uuid) returns bigint
language plpgsql security definer set search_path = '' as $$
declare v bigint;
begin
  update block_party.transfers set claimed_at = now()
  where id = p_id and to_id = (select auth.uid()) and claimed_at is null
  returning amount into v;
  return v;
end;
$$;

-- Privileges: start from nothing (the schema's default grants give everything to anon too).
revoke all on block_party.messages, block_party.door_notes, block_party.transfers from anon, authenticated;
grant select, insert, delete on block_party.messages, block_party.door_notes to authenticated;
grant select, insert on block_party.transfers to authenticated;
grant all on block_party.messages, block_party.door_notes, block_party.transfers to service_role;

revoke execute on function block_party.has_member(uuid, uuid) from public, anon;
revoke execute on function block_party.repays_own_loan(uuid, uuid, uuid, uuid) from public, anon;
revoke execute on function block_party.claim_transfer(uuid) from public, anon;
grant execute on function block_party.has_member(uuid, uuid) to authenticated, service_role;
grant execute on function block_party.repays_own_loan(uuid, uuid, uuid, uuid) to authenticated, service_role;
grant execute on function block_party.claim_transfer(uuid) to authenticated, service_role;

alter table block_party.messages enable row level security;
alter table block_party.door_notes enable row level security;
alter table block_party.transfers enable row level security;

create policy "members read chat" on block_party.messages for select to authenticated
  using (block_party.is_member(street_id));
create policy "members post as themselves" on block_party.messages for insert to authenticated
  with check (author_id = (select auth.uid()) and block_party.is_member(street_id));
create policy "author or admin deletes a message" on block_party.messages for delete to authenticated
  using (author_id = (select auth.uid()) or block_party.is_admin(street_id));

create policy "members read door notes" on block_party.door_notes for select to authenticated
  using (block_party.is_member(street_id));
create policy "members leave notes as themselves" on block_party.door_notes for insert to authenticated
  with check (author_id = (select auth.uid()) and block_party.is_member(street_id));
create policy "author, shop owner or admin removes a note" on block_party.door_notes for delete to authenticated
  using (
    author_id = (select auth.uid())
    or block_party.is_admin(street_id)
    or exists (select 1 from block_party.lots l where l.street_id = door_notes.street_id
               and l.lot_id = door_notes.lot_id and l.owner_id = (select auth.uid()))
  );

-- Players see money they sent or received; sending goes to someone else on the same street.
create policy "see own transfers" on block_party.transfers for select to authenticated
  using ((from_id = (select auth.uid()) or to_id = (select auth.uid())) and block_party.is_member(street_id));
create policy "send money as yourself to a street member" on block_party.transfers for insert to authenticated
  with check (
    from_id = (select auth.uid())
    and claimed_at is null
    and block_party.is_member(street_id)
    and block_party.has_member(street_id, to_id)
    and (kind <> 'repayment' or block_party.repays_own_loan(loan_id, street_id, from_id, to_id))
  );

-- Live updates for chat, notes and incoming money.
alter publication supabase_realtime add table block_party.messages, block_party.door_notes, block_party.transfers;
