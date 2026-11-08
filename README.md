# Block_Party

## Database

Block Party uses the **shared Supabase project** (`dpflpwoivvpfvainzwgd`,
also home to Not A Book Club) rather than a project of its own, so people
have **one login across all the shared apps**.

- `public` schema — shared by every app: `profiles` (one per person,
  auto-created on signup) and `get_app_secret()` for Edge Function secrets.
  Don't put Block Party tables here.
- `block_party` schema — all of Block Party's own tables, functions and
  RLS policies. It already exists, with the same default grants as `public`
  (still enable RLS on every table).

Client setup:

```ts
createClient<Database, 'block_party'>(SUPABASE_URL, SUPABASE_ANON_KEY, {
  db: { schema: 'block_party' },
})
```

To embed profiles (`select('*, profiles(display_name)')`), point foreign
keys at `public.profiles(id)` and add a view in this schema:
`create view block_party.profiles with (security_invoker = true) as select * from public.profiles;`
(see Not A Book Club's `0060` migration for the same pattern).

`block_party` must be listed under Dashboard → Project Settings → Data API →
Exposed schemas before the client can reach it.
