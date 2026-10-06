<?php
// Numeric customer credentials for every automatic account-creation path (single, trial, bulk).
//
// Usernames are 10 random digits and passwords 16 random digits, drawn one digit at a time from
// random_int() (the operating system's CSPRNG). Nothing is derived from time, counters or
// mt_rand(). Both are returned as PHP strings and must stay strings end to end (database columns
// VARCHAR, JSON string values, form fields) so leading zeros survive: "0042..." is a different
// account from "42...".
//
// Only call these when the panel itself is choosing the credentials for a NEW account. Never use
// them to replace an existing customer's credentials, credentials an operator typed in, or the
// administrator login. Do not log the returned values.

final class FlixCredentials
{
    const USERNAME_DIGITS = 10;
    const PASSWORD_DIGITS = 16;
    const MAX_ATTEMPTS = 25;

    /** A string of $length random digits 0-9 (leading zeros allowed). */
    public static function digits($length)
    {
        $length = (int)$length;
        if ($length < 1) {
            throw new InvalidArgumentException('length must be at least 1');
        }
        $out = '';
        for ($i = 0; $i < $length; $i++) {
            $out .= (string)random_int(0, 9);
        }
        return $out;
    }

    /**
     * A new username/password pair. $usernameTaken(string $username): bool must report whether the
     * username already exists on the content server (and in any local table). On a collision a new
     * username is drawn; after MAX_ATTEMPTS collisions it throws instead of returning a duplicate.
     */
    public static function generate($usernameTaken, $usernameDigits = self::USERNAME_DIGITS, $passwordDigits = self::PASSWORD_DIGITS)
    {
        if (!is_callable($usernameTaken)) {
            throw new InvalidArgumentException('a uniqueness check is required');
        }
        for ($attempt = 0; $attempt < self::MAX_ATTEMPTS; $attempt++) {
            $username = self::digits($usernameDigits);
            if (!call_user_func($usernameTaken, $username)) {
                return array('username' => $username, 'password' => self::digits($passwordDigits));
            }
        }
        throw new RuntimeException('Could not find a free username; try again.');
    }

    /**
     * $count new pairs for bulk creation, unique among themselves and against $usernameTaken.
     */
    public static function generateMany($count, $usernameTaken, $usernameDigits = self::USERNAME_DIGITS, $passwordDigits = self::PASSWORD_DIGITS)
    {
        $count = (int)$count;
        if ($count < 1 || $count > 1000) {
            throw new InvalidArgumentException('count must be between 1 and 1000');
        }
        $picked = array();
        $out = array();
        $check = function ($username) use (&$picked, $usernameTaken) {
            return isset($picked[$username]) || call_user_func($usernameTaken, $username);
        };
        while (count($out) < $count) {
            $pair = self::generate($check, $usernameDigits, $passwordDigits);
            $picked[$pair['username']] = true;
            $out[] = $pair;
        }
        return $out;
    }

    /**
     * Credentials for a create request: keeps operator-supplied values exactly as typed and only
     * generates the ones left blank. Existing accounts must not go through this at all.
     */
    public static function fillMissing($username, $password, $usernameTaken)
    {
        $username = trim((string)$username);
        $password = (string)$password;
        if ($username !== '' && $password !== '') {
            return array('username' => $username, 'password' => $password);
        }
        $generated = self::generate($usernameTaken);
        return array(
            'username' => $username !== '' ? $username : $generated['username'],
            'password' => $password !== '' ? $password : $generated['password'],
        );
    }
}
