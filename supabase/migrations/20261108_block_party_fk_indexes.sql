-- Index the foreign keys the performance advisor flagged, so leaving a street (which clears
-- that member's lots) and deleting an account (which removes their streets) don't scan tables.
create index if not exists lots_street_owner_idx on block_party.lots(street_id, owner_id);
create index if not exists streets_admin_idx on block_party.streets(admin_id);
