-- レコードウィジェット: shared lists (run once in Supabase SQL Editor)
create extension if not exists pgcrypto;

create table public.profiles (
  id uuid primary key references auth.users on delete cascade,
  name text not null check (char_length(name) between 1 and 20),
  is_admin boolean not null default false,
  created_at timestamptz not null default now()
);

create table public.playlists (
  id uuid primary key default gen_random_uuid(),
  owner uuid not null references public.profiles(id) on delete cascade,
  title text not null check (char_length(title) between 1 and 40),
  is_public boolean not null default false,
  like_count int not null default 0,
  created_at timestamptz not null default now()
);

create table public.playlist_tracks (
  id uuid primary key default gen_random_uuid(),
  playlist_id uuid not null references public.playlists(id) on delete cascade,
  position int not null,
  title text not null check (char_length(title) between 1 and 80),
  storage_path text not null,
  size_bytes bigint not null default 0
);
create index on public.playlist_tracks (playlist_id, position);

create table public.likes (
  playlist_id uuid not null references public.playlists(id) on delete cascade,
  user_id uuid not null references public.profiles(id) on delete cascade,
  created_at timestamptz not null default now(),
  primary key (playlist_id, user_id)
);

-- helpers ------------------------------------------------------------------------------
create function public.is_admin() returns boolean
language sql stable security definer set search_path = public as $$
  select coalesce((select is_admin from public.profiles where id = auth.uid()), false)
$$;

-- the first person to pick a nickname (you) becomes the admin
create function public.first_is_admin() returns trigger
language plpgsql security definer set search_path = public as $$
begin
  if not exists (select 1 from public.profiles where is_admin) then
    new.is_admin := true;
  else
    new.is_admin := false;
  end if;
  return new;
end $$;
create trigger profiles_first_admin before insert on public.profiles
  for each row execute function public.first_is_admin();

-- like_count kept by the database, not by apps
create function public.count_likes() returns trigger
language plpgsql security definer set search_path = public as $$
begin
  if tg_op = 'INSERT' then
    update public.playlists set like_count = like_count + 1 where id = new.playlist_id;
  else
    update public.playlists set like_count = greatest(like_count - 1, 0) where id = old.playlist_id;
  end if;
  return null;
end $$;
create trigger likes_count after insert or delete on public.likes
  for each row execute function public.count_likes();

-- total uploaded bytes (to warn before the free 1 GB fills up)
create function public.storage_used() returns bigint
language sql stable security definer set search_path = public as $$
  select coalesce(sum(size_bytes), 0)::bigint from public.playlist_tracks
$$;

-- column rights: apps can never set is_admin or like_count -------------------------------
revoke insert, update on public.profiles from anon, authenticated;
grant insert (id, name), update (name) on public.profiles to authenticated;
revoke insert, update on public.playlists from anon, authenticated;
grant insert (owner, title, is_public), update (title, is_public) on public.playlists to authenticated;
revoke all on public.likes from anon;

-- row rules ----------------------------------------------------------------------------
alter table public.profiles enable row level security;
alter table public.playlists enable row level security;
alter table public.playlist_tracks enable row level security;
alter table public.likes enable row level security;

create policy "profiles readable" on public.profiles for select to authenticated using (true);
create policy "own profile" on public.profiles for insert to authenticated with check (id = auth.uid());
create policy "rename self" on public.profiles for update to authenticated using (id = auth.uid());

create policy "see public or own" on public.playlists for select to authenticated
  using (is_public or owner = auth.uid() or public.is_admin());
create policy "make own" on public.playlists for insert to authenticated with check (owner = auth.uid());
create policy "edit own" on public.playlists for update to authenticated using (owner = auth.uid());
create policy "delete own or admin" on public.playlists for delete to authenticated
  using (owner = auth.uid() or public.is_admin());

create policy "tracks of visible lists" on public.playlist_tracks for select to authenticated
  using (exists (select 1 from public.playlists p where p.id = playlist_id
                 and (p.is_public or p.owner = auth.uid() or public.is_admin())));
create policy "tracks of own lists" on public.playlist_tracks for all to authenticated
  using (exists (select 1 from public.playlists p where p.id = playlist_id and p.owner = auth.uid()))
  with check (exists (select 1 from public.playlists p where p.id = playlist_id and p.owner = auth.uid()));

create policy "my likes" on public.likes for select to authenticated using (user_id = auth.uid());
create policy "like public lists" on public.likes for insert to authenticated
  with check (user_id = auth.uid() and exists (select 1 from public.playlists p where p.id = playlist_id and p.is_public));
create policy "unlike" on public.likes for delete to authenticated using (user_id = auth.uid());

-- audio files: private bucket, folder per user -----------------------------------------
insert into storage.buckets (id, name, public, file_size_limit, allowed_mime_types)
values ('audio', 'audio', false, 52428800, array['audio/mp4', 'audio/m4a', 'audio/x-m4a', 'audio/aac', 'audio/mpeg'])
on conflict (id) do nothing;

create policy "upload to own folder" on storage.objects for insert to authenticated
  with check (bucket_id = 'audio' and (storage.foldername(name))[1] = auth.uid()::text);
create policy "listen to own or public" on storage.objects for select to authenticated
  using (bucket_id = 'audio' and (
    (storage.foldername(name))[1] = auth.uid()::text
    or public.is_admin()
    or exists (select 1 from public.playlist_tracks t join public.playlists p on p.id = t.playlist_id
               where t.storage_path = name and p.is_public)));
create policy "delete own or admin" on storage.objects for delete to authenticated
  using (bucket_id = 'audio' and ((storage.foldername(name))[1] = auth.uid()::text or public.is_admin()));
