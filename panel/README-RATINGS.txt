FLIX TOWN — TMDB RATINGS AND TRENDING (panel add-on for app 3.6.0 and later)
===========================================================================

What this adds (3 NEW files, nothing existing is replaced)
-----------------------------------------------------------
* ratings.php     The app sends up to 12 titles at a time (type, title, year and TMDB ID when the
                  Xtream server has one) and gets TMDB's vote_average for each, or null when there
                  is no reliable rating.
* trending.php    This week's TMDB trending movies and TV shows (trending/all/week, up to 60).
                  The app keeps only the titles that exist on your Xtream server.
* tmdb-match.php  Shared title matching used by the two files above (not a page by itself).

They use what your panel already has:
* the TMDB key saved in the panel settings (settings table, name "tmdb_key"; the same key tmdb.php
  uses for cast and trailers), and
* the existing tmdb_cache table (ratings are cached 7 days, "no rating" 2 days, trending 6 hours).
No database changes, no new settings, and your config (private/config.php) is not touched.


Upload
------
Upload the 3 files from the "api" folder in this ZIP into the folder on your server that already
contains bootstrap.php, config.php and tmdb.php — the folder behind

    https://panelsandapps.com/panels/flixtown2027/api/

After uploading, that folder contains (next to your existing files):

    ratings.php
    trending.php
    tmdb-match.php

Do not upload anything into "private", and do not replace bootstrap.php, config.php or tmdb.php.


Check it
--------
Open in a browser:

    https://panelsandapps.com/panels/flixtown2027/api/trending.php

You should see {"items":[{"rank":1,"media":"movie", ... }]}.
* {"items":[],"unavailable":"TMDB key not set in the panel"} → add your TMDB key in the panel.
* {"error":"TMDB trending is unavailable right now"} → the server could not reach TMDB; try later.

ratings.php only answers POST requests from the app; opening it in a browser shows
{"error":"POST required"}, which is expected.


How titles are matched
----------------------
* The TMDB ID from your Xtream server is used first, but only if that TMDB record has the same
  title (providers sometimes store a wrong ID).
* Otherwise TMDB is searched by title and type (movie / TV), with the year when it is known.
  Provider decorations are ignored: "EN - ", "4K-EN - ", "|EN| ", "[4K]", "(2019)" and so on.
* Only an exact title match counts, with the year within one. Without a year, the title must be
  unique on TMDB (two series called "The Office" → no rating rather than the wrong one).
* A rating needs at least 20 TMDB votes. Otherwise the app shows no rating at all — never a
  default value.


If you do not upload these files
--------------------------------
App 3.6.0 still works: titles simply show no rating, and Home has no "Trending Now" row (it is
never filled with made-up trending titles). Everything else is unchanged.
