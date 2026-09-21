<?php
declare(strict_types=1);

require_once __DIR__ . '/db.php';

/**
 * Libsodium secretbox encryption used ONLY for the short-lived window
 * between a phone submitting Xtream credentials during pairing and the TV
 * fetching + acknowledging them. The pairing row (and this ciphertext) is
 * deleted once the TV ACKs; nothing here is meant as long-term storage.
 */
function ft_sodium_key(): string
{
    $hex = ft_secrets()['sodium_key_hex'] ?? '';
    if (!is_string($hex) || strlen($hex) !== SODIUM_CRYPTO_SECRETBOX_KEYBYTES * 2) {
        throw new RuntimeException('sodium_key_hex must be ' . (SODIUM_CRYPTO_SECRETBOX_KEYBYTES * 2) . ' hex characters');
    }
    return sodium_hex2bin($hex);
}

function ft_encrypt(string $plaintext): string
{
    $key = ft_sodium_key();
    $nonce = random_bytes(SODIUM_CRYPTO_SECRETBOX_NONCEBYTES);
    $cipherText = sodium_crypto_secretbox($plaintext, $nonce, $key);
    return base64_encode($nonce . $cipherText);
}

function ft_decrypt(string $encoded): string
{
    $key = ft_sodium_key();
    $raw = base64_decode($encoded, true);
    if ($raw === false || strlen($raw) <= SODIUM_CRYPTO_SECRETBOX_NONCEBYTES) {
        throw new RuntimeException('Invalid ciphertext');
    }
    $nonce = substr($raw, 0, SODIUM_CRYPTO_SECRETBOX_NONCEBYTES);
    $cipherText = substr($raw, SODIUM_CRYPTO_SECRETBOX_NONCEBYTES);
    $plaintext = sodium_crypto_secretbox_open($cipherText, $nonce, $key);
    if ($plaintext === false) {
        throw new RuntimeException('Failed to decrypt payload');
    }
    return $plaintext;
}
