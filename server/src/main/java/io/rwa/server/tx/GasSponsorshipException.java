package io.rwa.server.tx;

public class GasSponsorshipException extends RuntimeException {

    private final String reasonCode;
    private final GasPreflightResult preflightResult;

    public GasSponsorshipException(String reasonCode, String message) {
        this(reasonCode, message, null, null);
    }

    public GasSponsorshipException(
        String reasonCode,
        String message,
        GasPreflightResult preflightResult,
        Throwable cause
    ) {
        super(message, cause);
        this.reasonCode = reasonCode;
        this.preflightResult = preflightResult;
    }

    public String getReasonCode() {
        return reasonCode;
    }

    public GasPreflightResult getPreflightResult() {
        return preflightResult;
    }
}

