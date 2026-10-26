<?php
// Flix Town seasonal splash settings. Not reachable from the web (.htaccess).
return [
    // Public address of the api/ folder: picture links are built from it. Must be https and the same
    // host the app uses for the panel (the app ignores pictures on any other host).
    'public_api_base' => 'https://panelsandapps.com/panels/flixtown2027/api/',
    // Time zone used only for "Selected today" on the admin page (each TV uses its own date).
    'admin_timezone' => 'America/Denver',
];
