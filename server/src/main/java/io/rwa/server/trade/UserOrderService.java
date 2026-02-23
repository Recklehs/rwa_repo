package io.rwa.server.trade;

import java.math.BigInteger;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class UserOrderService {

    private final NamedParameterJdbcTemplate jdbcTemplate;

    public UserOrderService(NamedParameterJdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public void recordListSubmitted(
        UUID sellerUserId,
        BigInteger tokenId,
        BigInteger amount,
        BigInteger unitPrice,
        String idempotencyKey
    ) {
        jdbcTemplate.update(
            """
                INSERT INTO user_orders (
                    seller_user_id,
                    token_id,
                    amount,
                    unit_price,
                    order_type,
                    order_status,
                    idempotency_key,
                    created_at,
                    updated_at
                ) VALUES (
                    :sellerUserId,
                    :tokenId,
                    :amount,
                    :unitPrice,
                    'LIST',
                    'SUBMITTED',
                    :idempotencyKey,
                    now(),
                    now()
                )
                ON CONFLICT (seller_user_id, idempotency_key, order_type)
                DO NOTHING
                """,
            new MapSqlParameterSource()
                .addValue("sellerUserId", sellerUserId)
                .addValue("tokenId", tokenId)
                .addValue("amount", amount)
                .addValue("unitPrice", unitPrice)
                .addValue("idempotencyKey", idempotencyKey)
        );
    }

    public void recordCancelSubmitted(UUID sellerUserId, BigInteger listingId, String idempotencyKey) {
        jdbcTemplate.update(
            """
                INSERT INTO user_orders (
                    seller_user_id,
                    listing_id,
                    order_type,
                    order_status,
                    idempotency_key,
                    created_at,
                    updated_at
                ) VALUES (
                    :sellerUserId,
                    :listingId,
                    'CANCEL',
                    'SUBMITTED',
                    :idempotencyKey,
                    now(),
                    now()
                )
                ON CONFLICT (seller_user_id, idempotency_key, order_type)
                DO NOTHING
                """,
            new MapSqlParameterSource()
                .addValue("sellerUserId", sellerUserId)
                .addValue("listingId", listingId)
                .addValue("idempotencyKey", idempotencyKey)
        );
    }

    public List<Map<String, Object>> findBySeller(UUID sellerUserId, int limit) {
        return jdbcTemplate.queryForList(
            """
                SELECT id,
                       seller_user_id,
                       listing_id,
                       token_id,
                       amount,
                       unit_price,
                       order_type,
                       order_status,
                       idempotency_key,
                       created_at,
                       updated_at
                FROM user_orders
                WHERE seller_user_id = :sellerUserId
                ORDER BY created_at DESC
                LIMIT :limit
                """,
            new MapSqlParameterSource()
                .addValue("sellerUserId", sellerUserId)
                .addValue("limit", Math.max(1, limit))
        );
    }
}
