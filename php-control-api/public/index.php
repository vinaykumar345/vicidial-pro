<?php

declare(strict_types=1);

use VicidialControl\Auth;
use VicidialControl\Config;
use VicidialControl\JsonResponse;
use VicidialControl\VicidialClient;

require_once __DIR__ . '/../src/Config.php';
require_once __DIR__ . '/../src/JsonResponse.php';
require_once __DIR__ . '/../src/Auth.php';
require_once __DIR__ . '/../src/VicidialClient.php';

/** @return mysqli */
function openVicidialDb(Config $config)
{
    $host = $config->get('VICIDIAL_DB_HOST', 'db');
    $port = (int)$config->get('VICIDIAL_DB_PORT', '3306');
    $name = $config->require('VICIDIAL_DB_NAME');
    $user = $config->require('VICIDIAL_DB_USER');
    $pass = $config->require('VICIDIAL_DB_PASS');

    $db = @new mysqli($host, $user, $pass, $name, $port);
    if ($db->connect_errno) {
        throw new RuntimeException('DB connection failed: ' . $db->connect_error);
    }
    $db->set_charset('utf8mb4');
    return $db;
}

/** @return array{ok: bool, message: string, fullName: string, campaigns: array<int, array{id: string, name: string}>} */
function getAssignedCampaignsForAgent(Config $config, string $agentUser, string $agentPassword): array
{
    $db = openVicidialDb($config);
    try {
        $stmt = $db->prepare(
            "SELECT full_name,user_group FROM vicidial_users WHERE user=? AND pass=? AND user_level > 0 AND active='Y' LIMIT 1"
        );
        if ($stmt === false) {
            throw new RuntimeException('Failed to prepare user auth statement');
        }
        $stmt->bind_param('ss', $agentUser, $agentPassword);
        $stmt->execute();
        $res = $stmt->get_result();
        $userRow = $res ? $res->fetch_assoc() : null;
        $stmt->close();

        if (!$userRow || !is_array($userRow)) {
            return [
                'ok' => false,
                'message' => 'Invalid user ID or password',
                'fullName' => '',
                'campaigns' => [],
            ];
        }

        $fullName = trim((string)($userRow['full_name'] ?? ''));
        $userGroup = trim((string)($userRow['user_group'] ?? ''));

        $stmt = $db->prepare("SELECT allowed_campaigns FROM vicidial_user_groups WHERE user_group=? LIMIT 1");
        if ($stmt === false) {
            throw new RuntimeException('Failed to prepare campaign scope statement');
        }
        $stmt->bind_param('s', $userGroup);
        $stmt->execute();
        $res = $stmt->get_result();
        $groupRow = $res ? $res->fetch_assoc() : null;
        $stmt->close();

        $allowedRaw = strtoupper(trim((string)($groupRow['allowed_campaigns'] ?? '')));

        $campaignRows = [];
        if (strpos($allowedRaw, 'ALL-CAMPAIGNS') !== false) {
            $res = $db->query("SELECT campaign_id,campaign_name FROM vicidial_campaigns WHERE active='Y' ORDER BY campaign_name,campaign_id");
            if ($res) {
                while ($row = $res->fetch_assoc()) {
                    $campaignRows[] = [
                        'id' => trim((string)($row['campaign_id'] ?? '')),
                        'name' => trim((string)($row['campaign_name'] ?? '')),
                    ];
                }
                $res->free();
            }
        } else {
            preg_match_all('/[A-Z0-9_-]+/', $allowedRaw, $matches);
            $allowedIds = array_values(array_unique($matches[0] ?? []));

            if (count($allowedIds) > 0) {
                $quotedIds = array_map(static fn(string $id): string => "'" . $db->real_escape_string($id) . "'", $allowedIds);
                $inSql = implode(',', $quotedIds);
                $sql = "SELECT campaign_id,campaign_name FROM vicidial_campaigns WHERE active='Y' AND campaign_id IN ($inSql) ORDER BY campaign_name,campaign_id";
                $res = $db->query($sql);
                if ($res) {
                    while ($row = $res->fetch_assoc()) {
                        $campaignRows[] = [
                            'id' => trim((string)($row['campaign_id'] ?? '')),
                            'name' => trim((string)($row['campaign_name'] ?? '')),
                        ];
                    }
                    $res->free();
                }
            }
        }

        $campaignRows = array_values(array_filter($campaignRows, static fn(array $row): bool => $row['id'] !== ''));

        return [
            'ok' => true,
            'message' => count($campaignRows) > 0 ? 'Assigned campaigns loaded' : 'No active assigned campaigns found',
            'fullName' => $fullName,
            'campaigns' => $campaignRows,
        ];
    } finally {
        $db->close();
    }
}

/** @return array{ok: bool, message: string, phoneLogin: string, phonePass: string} */
function getAgentPhoneLogin(Config $config, string $agentUser, string $agentPassword): array
{
    $db = openVicidialDb($config);
    try {
        $stmt = $db->prepare(
            "SELECT phone_login,phone_pass FROM vicidial_users WHERE user=? AND pass=? AND user_level > 0 AND active='Y' LIMIT 1"
        );
        if ($stmt === false) {
            throw new RuntimeException('Failed to prepare agent phone login statement');
        }

        $stmt->bind_param('ss', $agentUser, $agentPassword);
        $stmt->execute();
        $res = $stmt->get_result();
        $row = $res ? $res->fetch_assoc() : null;
        $stmt->close();

        if (!$row || !is_array($row)) {
            return ['ok' => false, 'message' => 'Invalid user ID or password', 'phoneLogin' => '', 'phonePass' => ''];
        }

        $phoneLogin = trim((string)($row['phone_login'] ?? ''));
        $phonePass = trim((string)($row['phone_pass'] ?? ''));

        if ($phoneLogin === '' || $phonePass === '') {
            return [
                'ok' => false,
                'message' => 'Agent user is missing phone_login or phone_pass in vicidial_users',
                'phoneLogin' => '',
                'phonePass' => '',
            ];
        }

        return ['ok' => true, 'message' => 'Agent phone login loaded', 'phoneLogin' => $phoneLogin, 'phonePass' => $phonePass];
    } finally {
        $db->close();
    }
}

/** @return array{ok: bool, message: string, liveAgent: array<string, string>} */
function getLiveAgentSession(Config $config, string $agentUser): array
{
    $db = openVicidialDb($config);
    try {
        $stmt = $db->prepare(
            "SELECT user,campaign_id,server_ip,conf_exten,extension,status
            FROM vicidial_live_agents
            WHERE user=?
            ORDER BY last_update_time DESC
            LIMIT 1"
        );
        if ($stmt === false) {
            throw new RuntimeException('Failed to prepare live agent verification statement');
        }

        $stmt->bind_param('s', $agentUser);
        $stmt->execute();
        $res = $stmt->get_result();
        $row = $res ? $res->fetch_assoc() : null;
        $stmt->close();

        if (!$row || !is_array($row)) {
            return ['ok' => false, 'message' => 'No live agent session found after login', 'liveAgent' => []];
        }

        return [
            'ok' => true,
            'message' => 'Agent live session created',
            'liveAgent' => [
                'agentUser' => trim((string)($row['user'] ?? '')),
                'campaignId' => trim((string)($row['campaign_id'] ?? '')),
                'serverIp' => trim((string)($row['server_ip'] ?? '')),
                'sessionId' => trim((string)($row['conf_exten'] ?? '')),
                'extension' => trim((string)($row['extension'] ?? '')),
                'status' => trim((string)($row['status'] ?? '')),
            ],
        ];
    } finally {
        $db->close();
    }
}

function inferAgentLoginUrl(string $agcApiUrl): string
{
    if (preg_match('#/api\.php(?:\?.*)?$#', $agcApiUrl) === 1) {
        return preg_replace('#/api\.php(?:\?.*)?$#', '/vicidial.php', $agcApiUrl) ?: $agcApiUrl;
    }

    return rtrim(dirname($agcApiUrl), '/') . '/vicidial.php';
}

/** @return array{ok: bool, message: string, fullName: string, userLevel: int, userGroup: string} */
function validateUiUser(Config $config, string $username, string $password): array
{
    $db = openVicidialDb($config);
    try {
        $stmt = $db->prepare(
            "SELECT full_name,user_level,user_group FROM vicidial_users WHERE user=? AND pass=? AND active='Y' LIMIT 1"
        );
        if ($stmt === false) {
            throw new RuntimeException('Failed to prepare UI auth statement');
        }

        $stmt->bind_param('ss', $username, $password);
        $stmt->execute();
        $res = $stmt->get_result();
        $row = $res ? $res->fetch_assoc() : null;
        $stmt->close();

        if (!$row || !is_array($row)) {
            return [
                'ok' => false,
                'message' => 'Invalid username or password',
                'fullName' => '',
                'userLevel' => 0,
                'userGroup' => '',
            ];
        }

        return [
            'ok' => true,
            'message' => 'Authenticated',
            'fullName' => trim((string)($row['full_name'] ?? '')),
            'userLevel' => (int)($row['user_level'] ?? 0),
            'userGroup' => trim((string)($row['user_group'] ?? '')),
        ];
    } finally {
        $db->close();
    }
}

/** @return array{ok: bool, message: string, lead: array<string, string|int|null>} */
function getActiveLeadForAgent(Config $config, string $agentUser): array
{
    $db = openVicidialDb($config);
    try {
        $stmt = $db->prepare(
            "SELECT
                vla.lead_id,
                vla.status AS agent_status,
                vl.phone_number,
                vl.alt_phone,
                vl.first_name,
                vl.last_name,
                vl.address1,
                vl.address2,
                vl.address3,
                vl.city,
                vl.state,
                vl.postal_code,
                vl.vendor_lead_code,
                vl.source_id,
                vl.comments
            FROM vicidial_live_agents vla
            LEFT JOIN vicidial_list vl ON vl.lead_id = vla.lead_id
            WHERE vla.user=?
            LIMIT 1"
        );
        if ($stmt === false) {
            throw new RuntimeException('Failed to prepare active lead statement');
        }

        $stmt->bind_param('s', $agentUser);
        $stmt->execute();
        $res = $stmt->get_result();
        $row = $res ? $res->fetch_assoc() : null;
        $stmt->close();

        if (!$row || !is_array($row) || (int)($row['lead_id'] ?? 0) < 1) {
            return [
                'ok' => false,
                'message' => 'No active customer found for this agent',
                'lead' => [],
            ];
        }

        $firstName = trim((string)($row['first_name'] ?? ''));
        $lastName = trim((string)($row['last_name'] ?? ''));
        $fullName = trim($firstName . ' ' . $lastName);

        return [
            'ok' => true,
            'message' => 'Active customer loaded',
            'lead' => [
                'leadId' => (int)($row['lead_id'] ?? 0),
                'fullName' => $fullName,
                'phoneNumber' => trim((string)($row['phone_number'] ?? '')),
                'altPhone' => trim((string)($row['alt_phone'] ?? '')),
                'address1' => trim((string)($row['address1'] ?? '')),
                'address2' => trim((string)($row['address2'] ?? '')),
                'address3' => trim((string)($row['address3'] ?? '')),
                'city' => trim((string)($row['city'] ?? '')),
                'state' => trim((string)($row['state'] ?? '')),
                'postalCode' => trim((string)($row['postal_code'] ?? '')),
                'vendorLeadCode' => trim((string)($row['vendor_lead_code'] ?? '')),
                'sourceId' => trim((string)($row['source_id'] ?? '')),
                'comments' => trim((string)($row['comments'] ?? '')),
                'agentStatus' => trim((string)($row['agent_status'] ?? '')),
            ],
        ];
    } finally {
        $db->close();
    }
}

/** @return array{ok: bool, message: string, sessions: array<int, array<string, string|int>>} */
function discoverLiveSessions(
    Config $config,
    string $adminUser,
    string $adminPassword,
    string $agentFilter,
    int $limit
): array {
    $auth = validateUiUser($config, $adminUser, $adminPassword);
    if (!$auth['ok']) {
        return ['ok' => false, 'message' => $auth['message'], 'sessions' => []];
    }
    if ($auth['userLevel'] < 8) {
        return ['ok' => false, 'message' => 'Admin permission required', 'sessions' => []];
    }

    $db = openVicidialDb($config);
    try {
        $limit = max(1, min($limit, 100));

        $sql = "
            SELECT
                vla.user,
                vu.full_name,
                vla.campaign_id,
                vla.server_ip,
                vla.conf_exten,
                vla.extension,
                vla.status,
                DATE_FORMAT(vla.last_state_change, '%Y-%m-%d %H:%i:%s') AS last_state_change
            FROM vicidial_live_agents vla
            LEFT JOIN vicidial_users vu ON vu.user = vla.user
        ";

        if ($agentFilter !== '') {
            $sql .= " WHERE vla.user = ? ";
        }

        $sql .= " ORDER BY vla.last_state_change DESC LIMIT ?";

        $stmt = $db->prepare($sql);
        if ($stmt === false) {
            throw new RuntimeException('Failed to prepare live sessions statement');
        }

        if ($agentFilter !== '') {
            $stmt->bind_param('si', $agentFilter, $limit);
        } else {
            $stmt->bind_param('i', $limit);
        }

        $stmt->execute();
        $res = $stmt->get_result();
        $sessions = [];
        if ($res) {
            while ($row = $res->fetch_assoc()) {
                $sessions[] = [
                    'agentUser' => trim((string)($row['user'] ?? '')),
                    'fullName' => trim((string)($row['full_name'] ?? '')),
                    'campaignId' => trim((string)($row['campaign_id'] ?? '')),
                    'serverIp' => trim((string)($row['server_ip'] ?? '')),
                    'sessionId' => trim((string)($row['conf_exten'] ?? '')),
                    'extension' => trim((string)($row['extension'] ?? '')),
                    'status' => trim((string)($row['status'] ?? '')),
                    'lastStateChange' => trim((string)($row['last_state_change'] ?? '')),
                ];
            }
            $res->free();
        }
        $stmt->close();

        return [
            'ok' => true,
            'message' => count($sessions) > 0 ? 'Live sessions loaded' : 'No live sessions found',
            'sessions' => $sessions,
        ];
    } finally {
        $db->close();
    }
}

function renderPage(string $filename): void
{
    $file = __DIR__ . '/' . $filename;
    if (!is_file($file)) {
        JsonResponse::send(404, ['ok' => false, 'error' => 'UI page not found']);
        return;
    }

    http_response_code(200);
    header('Content-Type: text/html; charset=utf-8');
    readfile($file);
}

try {
    $config = Config::fromEnvFile(__DIR__ . '/../.env');

    $method = $_SERVER['REQUEST_METHOD'] ?? 'GET';
    $path = parse_url($_SERVER['REQUEST_URI'] ?? '/', PHP_URL_PATH) ?: '/';

    if ($method === 'GET' && $path === '/') {
        renderPage('landing.html');
        exit;
    }

    if ($method === 'GET' && $path === '/agent') {
        renderPage('agent.html');
        exit;
    }

    if ($method === 'GET' && $path === '/admin') {
        renderPage('admin.html');
        exit;
    }

    $apiKey = $config->require('APP_API_KEY');
    if (!Auth::requireApiKey($apiKey)) {
        JsonResponse::send(401, ['ok' => false, 'error' => 'Unauthorized']);
        exit;
    }

    $client = new VicidialClient(
        agcUrl: $config->require('VICIDIAL_AGC_API_URL'),
        nonAgentUrl: $config->require('VICIDIAL_NON_AGENT_API_URL'),
        apiUser: $config->require('VICIDIAL_API_USER'),
        apiPass: $config->require('VICIDIAL_API_PASS'),
        source: $config->get('VICIDIAL_SOURCE', 'php-control')
    );

    if ($method === 'GET' && $path === '/health') {
        JsonResponse::send(200, ['ok' => true, 'service' => 'php-control-api']);
        exit;
    }

    if ($method !== 'POST') {
        JsonResponse::send(405, ['ok' => false, 'error' => 'Method not allowed']);
        exit;
    }

    $input = json_decode(file_get_contents('php://input') ?: '{}', true);
    if (!is_array($input)) {
        JsonResponse::send(400, ['ok' => false, 'error' => 'Invalid JSON']);
        exit;
    }

    switch ($path) {
        case '/api/agent-campaigns': {
            $agentUser = trim((string)($input['agentUser'] ?? ''));
            $agentPassword = trim((string)($input['agentPassword'] ?? ''));

            if ($agentUser === '' || $agentPassword === '') {
                JsonResponse::send(422, ['ok' => false, 'error' => 'agentUser and agentPassword are required']);
                exit;
            }

            $campaignData = getAssignedCampaignsForAgent($config, $agentUser, $agentPassword);
            if (!$campaignData['ok']) {
                JsonResponse::send(401, ['ok' => false, 'message' => $campaignData['message']]);
                exit;
            }

            JsonResponse::send(200, [
                'ok' => true,
                'message' => $campaignData['message'],
                'agentUser' => $agentUser,
                'fullName' => $campaignData['fullName'],
                'campaigns' => $campaignData['campaigns'],
            ]);
            exit;
        }

        case '/api/agent-login': {
            $agentUser = trim((string)($input['agentUser'] ?? ''));
            $agentPassword = trim((string)($input['agentPassword'] ?? ''));
            $campaignId = strtoupper(trim((string)($input['campaignId'] ?? '')));

            if ($agentUser === '' || $agentPassword === '' || $campaignId === '') {
                JsonResponse::send(422, ['ok' => false, 'error' => 'agentUser, agentPassword and campaignId are required']);
                exit;
            }

            $campaignData = getAssignedCampaignsForAgent($config, $agentUser, $agentPassword);
            if (!$campaignData['ok']) {
                JsonResponse::send(401, ['ok' => false, 'message' => $campaignData['message']]);
                exit;
            }

            $allowed = false;
            foreach ($campaignData['campaigns'] as $campaign) {
                if (strtoupper((string)$campaign['id']) === $campaignId) {
                    $allowed = true;
                    break;
                }
            }

            if (!$allowed) {
                JsonResponse::send(403, ['ok' => false, 'message' => 'You do not have permission to log in to that campaign']);
                exit;
            }

            $phoneLogin = getAgentPhoneLogin($config, $agentUser, $agentPassword);
            if (!$phoneLogin['ok']) {
                JsonResponse::send(422, ['ok' => false, 'message' => $phoneLogin['message']]);
                exit;
            }

            $agentLoginUrl = trim($config->get('VICIDIAL_AGENT_LOGIN_URL'));
            if ($agentLoginUrl === '') {
                $agentLoginUrl = inferAgentLoginUrl($config->require('VICIDIAL_AGC_API_URL'));
            }
            $loginRes = $client->callAgentLogin($agentLoginUrl, [
                'phone_login' => $phoneLogin['phoneLogin'],
                'phone_pass' => $phoneLogin['phonePass'],
                'VD_login' => $agentUser,
                'VD_pass' => $agentPassword,
                'VD_campaign' => $campaignId,
                'SUBMIT' => 'SUBMIT',
            ]);

            if (!$loginRes['ok']) {
                JsonResponse::send(502, [
                    'ok' => false,
                    'message' => 'VICIdial agent login request failed',
                    'upstream' => $loginRes,
                ]);
                exit;
            }

            $liveSession = ['ok' => false];
            for ($i = 0; $i < 5; $i++) {
                $liveSession = getLiveAgentSession($config, $agentUser);
                if ($liveSession['ok']) break;
                usleep(800000);
            }

            JsonResponse::send(200, [
                'ok' => true,
                'message' => $liveSession['ok'] ? 'Agent logged in and live session created' : 'Agent login validated and ready to auto-connect',
                'agentUser' => $agentUser,
                'campaignId' => $campaignId,
                'fullName' => $campaignData['fullName'],
                'liveAgent' => $liveSession['ok'] ? $liveSession['liveAgent'] : [],
            ]);
            exit;
        }

        case '/api/ui-auth': {
            $username = trim((string)($input['username'] ?? ''));
            $password = trim((string)($input['password'] ?? ''));
            $panel = strtolower(trim((string)($input['panel'] ?? 'agent')));

            if ($username === '' || $password === '') {
                JsonResponse::send(422, ['ok' => false, 'error' => 'username and password are required']);
                exit;
            }

            $auth = validateUiUser($config, $username, $password);
            if (!$auth['ok']) {
                JsonResponse::send(401, ['ok' => false, 'message' => $auth['message']]);
                exit;
            }

            if ($panel === 'admin' && $auth['userLevel'] < 8) {
                JsonResponse::send(403, ['ok' => false, 'message' => 'Admin access requires user_level >= 8']);
                exit;
            }

            $payload = [
                'ok' => true,
                'message' => 'Authenticated',
                'username' => $username,
                'fullName' => $auth['fullName'],
                'userLevel' => $auth['userLevel'],
                'panel' => $panel,
            ];

            if ($panel === 'agent') {
                $campaignData = getAssignedCampaignsForAgent($config, $username, $password);
                if (!$campaignData['ok']) {
                    JsonResponse::send(403, ['ok' => false, 'message' => $campaignData['message']]);
                    exit;
                }
                $payload['campaigns'] = $campaignData['campaigns'];
            }

            JsonResponse::send(200, $payload);
            exit;
        }

        case '/api/live-sessions': {
            $adminUser = trim((string)($input['adminUser'] ?? ''));
            $adminPassword = trim((string)($input['adminPassword'] ?? ''));
            $agentUser = trim((string)($input['agentUser'] ?? ''));
            $limit = (int)($input['limit'] ?? 25);

            if ($adminUser === '' || $adminPassword === '') {
                JsonResponse::send(422, ['ok' => false, 'error' => 'adminUser and adminPassword are required']);
                exit;
            }

            $sessions = discoverLiveSessions($config, $adminUser, $adminPassword, $agentUser, $limit);
            if (!$sessions['ok']) {
                JsonResponse::send(403, ['ok' => false, 'message' => $sessions['message']]);
                exit;
            }

            JsonResponse::send(200, [
                'ok' => true,
                'message' => $sessions['message'],
                'sessions' => $sessions['sessions'],
            ]);
            exit;
        }

        case '/api/login-check': {
            $agentUser = trim((string)($input['agentUser'] ?? ''));
            if ($agentUser === '') {
                JsonResponse::send(422, ['ok' => false, 'error' => 'agentUser is required']);
                exit;
            }

            $res = $client->callNonAgent([
                'function' => 'agent_ingroup_info',
                'stage' => 'text',
                'agent_user' => $agentUser,
            ]);

            JsonResponse::send($res['ok'] ? 200 : 502, ['ok' => $res['ok'], 'upstream' => $res]);
            exit;
        }

        case '/api/agent-active-lead': {
            $agentUser = trim((string)($input['agentUser'] ?? ''));
            if ($agentUser === '') {
                JsonResponse::send(422, ['ok' => false, 'error' => 'agentUser is required']);
                exit;
            }

            $lead = getActiveLeadForAgent($config, $agentUser);
            JsonResponse::send($lead['ok'] ? 200 : 404, [
                'ok' => $lead['ok'],
                'message' => $lead['message'],
                'lead' => $lead['lead'],
            ]);
            exit;
        }

        case '/api/agent-action': {
            $agentUser = trim((string)($input['agentUser'] ?? ''));
            $action = strtolower(trim((string)($input['action'] ?? '')));
            $value = trim((string)($input['value'] ?? ''));
            $phoneNumber = trim((string)($input['phoneNumber'] ?? ''));
            $ingroupChoices = trim((string)($input['ingroupChoices'] ?? ''));
            $consultative = strtoupper(trim((string)($input['consultative'] ?? 'NO')));
            $dialOverride = strtoupper(trim((string)($input['dialOverride'] ?? 'NO')));

            if ($agentUser === '' || $action === '') {
                JsonResponse::send(422, ['ok' => false, 'error' => 'agentUser and action are required']);
                exit;
            }

            $params = ['agent_user' => $agentUser];
            switch ($action) {
                case 'pause':
                    $params['function'] = 'external_pause';
                    $params['value'] = 'PAUSE';
                    break;
                case 'resume':
                    $params['function'] = 'external_pause';
                    $params['value'] = 'RESUME';
                    break;
                case 'hangup':
                    $params['function'] = 'external_hangup';
                    $params['value'] = '1';
                    break;
                case 'logout':
                    $params['function'] = 'logout';
                    $params['value'] = 'LOGOUT';
                    break;
                case 'dispo':
                    if ($value === '') {
                        JsonResponse::send(422, ['ok' => false, 'error' => 'value is required for dispo']);
                        exit;
                    }
                    $params['function'] = 'external_status';
                    $params['value'] = strtoupper($value);
                    break;
                case 'dtmf':
                    if ($value === '') {
                        JsonResponse::send(422, ['ok' => false, 'error' => 'value is required for dtmf']);
                        exit;
                    }
                    $params['function'] = 'send_dtmf';
                    $params['value'] = strtoupper($value);
                    break;
                case 'park':
                    $params['function'] = 'park_call';
                    $params['value'] = 'PARK_CUSTOMER';
                    break;
                case 'grab':
                    $params['function'] = 'park_call';
                    $params['value'] = 'GRAB_CUSTOMER';
                    break;
                case 'park_ivr':
                    $params['function'] = 'park_call';
                    $params['value'] = 'PARK_IVR_CUSTOMER';
                    break;
                case 'grab_ivr':
                    $params['function'] = 'park_call';
                    $params['value'] = 'GRAB_IVR_CUSTOMER';
                    break;
                case 'blind_transfer':
                    if ($phoneNumber === '') {
                        JsonResponse::send(422, ['ok' => false, 'error' => 'phoneNumber is required for blind transfer']);
                        exit;
                    }
                    $params['function'] = 'transfer_conference';
                    $params['value'] = 'BLIND_TRANSFER';
                    $params['phone_number'] = $phoneNumber;
                    $params['dial_override'] = $dialOverride === 'YES' ? 'YES' : 'NO';
                    break;
                case 'dial_with_customer':
                    $params['function'] = 'transfer_conference';
                    $params['value'] = 'DIAL_WITH_CUSTOMER';
                    if ($phoneNumber !== '') {
                        $params['phone_number'] = $phoneNumber;
                        $params['dial_override'] = $dialOverride === 'YES' ? 'YES' : 'NO';
                    }
                    if ($ingroupChoices !== '') {
                        $params['ingroup_choices'] = $ingroupChoices;
                    }
                    $params['consultative'] = $consultative === 'YES' ? 'YES' : 'NO';
                    break;
                case 'park_customer_dial':
                    $params['function'] = 'transfer_conference';
                    $params['value'] = 'PARK_CUSTOMER_DIAL';
                    if ($phoneNumber !== '') {
                        $params['phone_number'] = $phoneNumber;
                    }
                    if ($ingroupChoices !== '') {
                        $params['ingroup_choices'] = $ingroupChoices;
                    }
                    $params['consultative'] = $consultative === 'YES' ? 'YES' : 'NO';
                    $params['dial_override'] = $dialOverride === 'YES' ? 'YES' : 'NO';
                    break;
                case 'local_closer':
                    if ($ingroupChoices === '') {
                        JsonResponse::send(422, ['ok' => false, 'error' => 'ingroupChoices is required for local closer']);
                        exit;
                    }
                    $params['function'] = 'transfer_conference';
                    $params['value'] = 'LOCAL_CLOSER';
                    $params['ingroup_choices'] = $ingroupChoices;
                    if ($phoneNumber !== '') {
                        $params['phone_number'] = $phoneNumber;
                    }
                    $params['consultative'] = $consultative === 'YES' ? 'YES' : 'NO';
                    break;
                case 'leave_vm':
                    $params['function'] = 'transfer_conference';
                    $params['value'] = 'LEAVE_VM';
                    break;
                case 'leave_3way':
                    $params['function'] = 'transfer_conference';
                    $params['value'] = 'LEAVE_3WAY_CALL';
                    break;
                case 'hangup_xfer':
                    $params['function'] = 'transfer_conference';
                    $params['value'] = 'HANGUP_XFER';
                    break;
                case 'hangup_both':
                    $params['function'] = 'transfer_conference';
                    $params['value'] = 'HANGUP_BOTH';
                    break;
                default:
                    JsonResponse::send(422, ['ok' => false, 'error' => 'Unsupported agent action']);
                    exit;
            }

            $res = $client->callAgc($params);

            if ($action === 'logout') {
                try {
                    $db = openVicidialDb($config);
                    $stmt = $db->prepare("DELETE FROM vicidial_live_agents WHERE user=?");
                    if ($stmt) { $stmt->bind_param('s', $agentUser); $stmt->execute(); $stmt->close(); }
                    $db->close();
                } catch (Throwable $ignored) {}
            }

            JsonResponse::send($res['ok'] ? 200 : 502, ['ok' => $res['ok'], 'message' => $action === 'logout' ? 'Agent logged out' : ($res['body'] ?? ''), 'upstream' => $res]);
            exit;
        }

        case '/api/external-dial': {
            $agentUser = trim((string)($input['agentUser'] ?? ''));
            $phoneNumber = preg_replace('/\D+/', '', (string)($input['phoneNumber'] ?? '')) ?: '';
            $phoneCode = trim((string)($input['phoneCode'] ?? '1'));

            if ($agentUser === '' || $phoneNumber === '') {
                JsonResponse::send(422, ['ok' => false, 'error' => 'agentUser and phoneNumber are required']);
                exit;
            }

            $res = $client->callAgc([
                'function' => 'external_dial',
                'agent_user' => $agentUser,
                'value' => $phoneNumber,
                'phone_code' => $phoneCode,
                'search' => 'YES',
                'preview' => 'NO',
                'focus' => 'YES',
            ]);

            JsonResponse::send($res['ok'] ? 200 : 502, ['ok' => $res['ok'], 'upstream' => $res]);
            exit;
        }

        case '/api/monitor':
        case '/api/barge': {
            $stage = $path === '/api/barge' ? 'BARGE' : 'MONITOR';
            $phoneLogin = trim((string)($input['phoneLogin'] ?? ''));
            $sessionId = preg_replace('/\D+/', '', (string)($input['sessionId'] ?? '')) ?: '';
            $serverIp = trim((string)($input['serverIp'] ?? ''));

            if ($phoneLogin === '' || $sessionId === '' || $serverIp === '') {
                JsonResponse::send(422, ['ok' => false, 'error' => 'phoneLogin, sessionId and serverIp are required']);
                exit;
            }

            $res = $client->callNonAgent([
                'function' => 'blind_monitor',
                'phone_login' => $phoneLogin,
                'session_id' => $sessionId,
                'server_ip' => $serverIp,
                'stage' => $stage,
            ]);

            JsonResponse::send($res['ok'] ? 200 : 502, ['ok' => $res['ok'], 'upstream' => $res]);
            exit;
        }

        default:
            JsonResponse::send(404, ['ok' => false, 'error' => 'Not found']);
            exit;
    }
} catch (Throwable $e) {
    JsonResponse::send(500, ['ok' => false, 'error' => $e->getMessage()]);
}
