<?php
declare(strict_types=1);

/**
 * Single source of truth for where the private backend (includes/, tools/,
 * storage/, secrets.php) lives relative to this deployment's web root.
 *
 * This file sits at the web root itself (DOCUMENT_ROOT for the
 * flixtown.panelsandapps.com subdomain). Every public entry point, at any
 * nesting depth, requires it the same way:
 *
 *   require_once $_SERVER['DOCUMENT_ROOT'] . '/bootstrap.php';
 *
 * so no entry point ever has to count "../" segments for its own depth, and
 * nothing here hardcodes a cPanel username — only the fixed relationship
 * between this web root and its private backend sibling.
 *
 * Real layout this assumes:
 *   <home>/public_html/flixtown/   <- this file's directory (web root)
 *   <home>/flixtown-backend/       <- FT_BACKEND_ROOT
 */
if (!defined('FT_BACKEND_ROOT')) {
    define('FT_BACKEND_ROOT', dirname(__DIR__, 2) . '/flixtown-backend');
}
