package io.rwa.server.publicdata;

import java.math.BigInteger;
import java.time.Instant;

public record IssuedTokenItem(
    BigInteger tokenId,
    String unitId,
    int unitNo,
    String classId,
    String kaptCode,
    String classKey,
    BigInteger baseTokenId,
    String docHash,
    Instant issuedAt,
    String classStatus,
    String unitStatus
) {}
