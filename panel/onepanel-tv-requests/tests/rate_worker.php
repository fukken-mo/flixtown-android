<?php
/*
 * One process hitting tvq_rate() on the real MySQL database, for the concurrency test.
 * Args: <work dir> <key> <max> <window seconds> <hits>. Prints how many hits were allowed.
 */
[$self, $work, $key, $max, $window, $hits] = $argv;
require "$work/includes/bootstrap.php";
require "$work/includes/tv_requests.php";
$allowed = 0;
for ($i = 0; $i < (int)$hits; $i++) if (tvq_rate($pdo, $key, (int)$max, (int)$window)) $allowed++;
echo $allowed;
