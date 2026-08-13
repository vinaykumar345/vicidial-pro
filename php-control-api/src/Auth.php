<?php

declare(strict_types=1);

namespace VicidialControl;

final class Auth
{
    public static function requireApiKey(string $expected): bool
    {
        $provided = $_SERVER['HTTP_X_API_KEY'] ?? '';
        return $expected !== '' && hash_equals($expected, $provided);
    }
}
