<?php

declare(strict_types=1);

namespace VicidialControl;

final class VicidialClient
{
    public function __construct(
        private readonly string $agcUrl,
        private readonly string $nonAgentUrl,
        private readonly string $apiUser,
        private readonly string $apiPass,
        private readonly string $source
    ) {
    }

    /** @param array<string, string> $params */
    public function callAgc(array $params): array
    {
        return $this->post($this->agcUrl, $params + $this->baseParams());
    }

    /** @param array<string, string> $params */
    public function callNonAgent(array $params): array
    {
        return $this->post($this->nonAgentUrl, $params + $this->baseParams());
    }

    /** @return array<string, string> */
    private function baseParams(): array
    {
        return [
            'source' => $this->source,
            'user' => $this->apiUser,
            'pass' => $this->apiPass,
        ];
    }

    /**
     * @param array<string, string> $params
     * @return array{ok: bool, status: int, body: string}
     */
    private function post(string $url, array $params): array
    {
        $ch = curl_init($url);
        if ($ch === false) {
            return ['ok' => false, 'status' => 0, 'body' => 'Curl init failed'];
        }

        curl_setopt_array($ch, [
            CURLOPT_POST => true,
            CURLOPT_RETURNTRANSFER => true,
            CURLOPT_TIMEOUT => 20,
            CURLOPT_POSTFIELDS => http_build_query($params),
            CURLOPT_HTTPHEADER => ['Content-Type: application/x-www-form-urlencoded'],
        ]);

        $body = curl_exec($ch);
        $code = (int) curl_getinfo($ch, CURLINFO_HTTP_CODE);
        $err = curl_error($ch);
        curl_close($ch);

        if ($body === false) {
            return ['ok' => false, 'status' => $code, 'body' => $err !== '' ? $err : 'Unknown request error'];
        }

        return ['ok' => $code >= 200 && $code < 300, 'status' => $code, 'body' => trim($body)];
    }
}
