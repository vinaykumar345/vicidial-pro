<?php

declare(strict_types=1);

namespace VicidialControl;

final class Config
{
    /** @var array<string, string> */
    private array $values;

    private function __construct(array $values)
    {
        $this->values = $values;
    }

    public static function fromEnvFile(string $envPath): self
    {
        $values = [];

        if (is_file($envPath)) {
            $lines = file($envPath, FILE_IGNORE_NEW_LINES | FILE_SKIP_EMPTY_LINES) ?: [];
            foreach ($lines as $line) {
                $trimmed = trim($line);
                if ($trimmed === '' || str_starts_with($trimmed, '#')) {
                    continue;
                }
                $parts = explode('=', $trimmed, 2);
                if (count($parts) !== 2) {
                    continue;
                }
                $key = trim($parts[0]);
                $val = trim($parts[1]);
                $values[$key] = $val;
            }
        }

        foreach ($_ENV as $k => $v) {
            if (is_string($v) && $v !== '') {
                $values[$k] = $v;
            }
        }

        // Fallback for runtimes where $_ENV is not fully populated.
        foreach (array_keys($_SERVER) as $k) {
            if (!is_string($k) || $k === '') {
                continue;
            }
            $v = getenv($k);
            if (is_string($v) && $v !== '') {
                $values[$k] = $v;
            }
        }

        return new self($values);
    }

    public function require(string $key): string
    {
        $val = $this->values[$key] ?? '';
        if ($val === '') {
            throw new \RuntimeException("Missing required config: {$key}");
        }
        return $val;
    }

    public function get(string $key, string $default = ''): string
    {
        return $this->values[$key] ?? $default;
    }
}
