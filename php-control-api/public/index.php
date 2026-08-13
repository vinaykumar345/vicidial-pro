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

try {
    $config = Config::fromEnvFile(__DIR__ . '/../.env');

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

    $method = $_SERVER['REQUEST_METHOD'] ?? 'GET';
    $path = parse_url($_SERVER['REQUEST_URI'] ?? '/', PHP_URL_PATH) ?: '/';

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

            JsonResponse::send(200, [
                'ok' => true,
                'message' => 'Agent login validated and ready to auto-connect',
                'agentUser' => $agentUser,
                'campaignId' => $campaignId,
                'fullName' => $campaignData['fullName'],
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
