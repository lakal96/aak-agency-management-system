CREATE TABLE `refresh_tokens` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `username` varchar(100) NOT NULL,
  `token_hash` varchar(255) NOT NULL,
  `expires_at` datetime(6) NOT NULL,
  `revoked` bit(1) NOT NULL DEFAULT b'0',
  `created_at` datetime(6) NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_refresh_tokens_token_hash` (`token_hash`),
  KEY `idx_refresh_tokens_username` (`username`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
