<?php
require_once __DIR__ . '/ui.php';

/**
 * Page shell.
 * page_top($title, $active, $opts) — $opts is optional and purely presentational:
 *   'subtitle' => string, 'eyebrow' => string,
 *   'crumbs'   => [['Customers','customers.php'], ['(505) 555-0101', null]],
 *   'actions'  => raw HTML for the header action area (defaults to "New Customer"),
 *   'header'   => false to skip the standard page header (page renders its own).
 */
function page_top(string $title, string $active = 'dashboard', array $opts = []): void {
  global $config;
  $sections = [
    'Workspace' => [
      'dashboard' => ['Dashboard', 'dashboard.php', 'dashboard'],
      'customers' => ['Customers', 'customers.php', 'users'],
      'payments'  => ['Payments', 'payments.php', 'card'],
      'renewals'  => ['Renewals', 'renewals.php', 'refresh'],
    ],
    'Services' => [
      'services'  => ['Services', 'services.php', 'layers'],
      'media'     => ['Media Requests', 'media-requests.php', 'film'],
      'playback-reports' => ['Playback Reports', 'playback-reports.php', 'alert'],
    ],
    'System' => [
      'activity'  => ['Activity', 'activity.php', 'activity'],
      'settings'  => ['Settings', 'settings.php', 'settings'],
    ],
  ];
  $admin = (string)($_SESSION['admin_name'] ?? 'Admin');
  $subtitle = isset($opts['subtitle']) ? (string)$opts['subtitle'] : '';
  $eyebrow = isset($opts['eyebrow']) ? (string)$opts['eyebrow'] : '';
  $crumbs = isset($opts['crumbs']) && is_array($opts['crumbs']) ? $opts['crumbs'] : [];
  $actions = array_key_exists('actions', $opts) ? (string)$opts['actions']
    : '<a class="btn btn--primary" href="customer-new.php">' . icon('plus') . '<span>New Customer</span></a>';
  $showHeader = !isset($opts['header']) || $opts['header'] !== false;

  echo '<!doctype html><html lang="en" data-theme="light"><head>' . op_head($title . ' · OnePanel') . '</head><body class="app">';
  echo '<a class="skip-link" href="#main">Skip to content</a>';

  // Sidebar
  echo '<aside class="sidebar" id="sidebar"><div class="sidebar__brand"><a href="dashboard.php" aria-label="OnePanel dashboard">' . brand_lockup('Master Control') . '</a></div>';
  echo '<nav class="sidebar__nav" aria-label="Main">';
  foreach ($sections as $label => $items) {
    echo '<div class="nav-section"><span class="nav-section__label">' . e($label) . '</span>';
    foreach ($items as $k => $n) {
      $is = $active === $k;
      echo '<a class="nav-link' . ($is ? ' is-active' : '') . '" href="' . e($n[1]) . '"' . ($is ? ' aria-current="page"' : '') . '>' . icon($n[2]) . '<span>' . e($n[0]) . '</span></a>';
    }
    echo '</div>';
  }
  echo '</nav>';
  echo '<div class="sidebar__foot"><div class="admin-chip"><span class="avatar">' . e(strtoupper(substr($admin, 0, 1))) . '</span><span class="admin-chip__text"><strong>' . e($admin) . '</strong><small>Administrator</small></span>'
     . '<a class="icon-btn" href="logout.php" title="Sign out" aria-label="Sign out">' . icon('logout') . '</a></div></div>';
  echo '</aside><div class="sidebar-scrim" data-nav-close></div>';

  // Main column
  echo '<div class="main">';
  echo '<header class="topbar"><div class="topbar__inner"><button class="icon-btn topbar__menu" type="button" data-nav-toggle aria-label="Open navigation" aria-controls="sidebar">' . icon('menu') . '</button>';
  echo '<nav class="crumbs" aria-label="Breadcrumb"><a href="dashboard.php">OnePanel</a>';
  foreach ($crumbs as $c) {
    echo icon('chevron-right');
    if (!empty($c[1])) echo '<a href="' . e((string)$c[1]) . '">' . e((string)$c[0]) . '</a>';
    else echo '<span aria-current="page">' . e((string)$c[0]) . '</span>';
  }
  if (!$crumbs) echo icon('chevron-right') . '<span aria-current="page">' . e($title) . '</span>';
  echo '</nav>';
  echo '<form class="topbar__search" action="customers.php" method="get" role="search">' . icon('search') . '<input name="q" inputmode="numeric" placeholder="Search customers by phone" aria-label="Search customers by phone" value=""><kbd>/</kbd></form>' . theme_toggle();
  echo '</div></header>';

  echo '<main class="content" id="main">';
  if ($showHeader) {
    echo '<div class="page-header"><div class="page-header__text">';
    if ($eyebrow !== '') echo '<span class="eyebrow">' . e($eyebrow) . '</span>';
    echo '<h1>' . e($title) . '</h1>';
    if ($subtitle !== '') echo '<p>' . e($subtitle) . '</p>';
    echo '</div>';
    if ($actions !== '') echo '<div class="page-header__actions">' . $actions . '</div>';
    echo '</div>';
  }
}

function page_bottom(): void {
  echo '</main><footer class="app-footer"><span>OnePanel</span><span>Flix Town · 505 TV · Cash App</span></footer></div>';
  $flash = flash_take();
  echo '<div class="toast-stack" id="toasts" aria-live="polite">';
  if ($flash) {
    echo '<div class="toast toast--' . e((string)$flash['tone']) . '">' . icon($flash['tone'] === 'success' ? 'check-circle' : 'info') . '<span>' . e((string)$flash['message']) . '</span><button type="button" class="toast__x" aria-label="Dismiss">' . icon('x') . '</button></div>';
  }
  echo '</div>';
  echo op_confirm_modal();
  echo '<div class="busy-overlay" id="busyOverlay" hidden><div class="busy-card"><span class="spinner"></span><strong>Working…</strong><p>Please keep this page open.</p></div></div>';
  echo '<script src="' . e(op_asset('assets/js/app.js')) . '"></script></body></html>';
}

/** Shared confirmation dialog (driven by data-confirm on forms). */
function op_confirm_modal(): string {
  return '<div class="modal" id="confirmModal" role="dialog" aria-modal="true" aria-labelledby="confirmTitle" hidden><div class="modal__card modal__card--sm">'
    . '<span class="modal__icon" data-confirm-icon>' . icon('alert') . '</span>'
    . '<h2 id="confirmTitle" data-confirm-title>Are you sure?</h2><p data-confirm-text></p>'
    . '<div class="modal__actions"><button type="button" class="btn btn--ghost" data-confirm-cancel>Cancel</button><button type="button" class="btn btn--danger" data-confirm-ok>Confirm</button></div>'
    . '</div></div>';
}
