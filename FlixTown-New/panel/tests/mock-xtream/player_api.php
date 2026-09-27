<?php
header('Content-Type: application/json');
echo json_encode(['user_info' => ['auth' => ($_GET['username'] ?? '') === 'testuser' && ($_GET['password'] ?? '') === 'testpass' ? 1 : 0, 'status' => 'Active']]);
