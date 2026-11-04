package lk.aak.agency.util;

import java.math.BigDecimal;
import java.math.RoundingMode;

/** Small number helpers shared by request validation. */
public final class Decimals {

    private Decimals() {
    }

    /** Two decimal places, half up (null stays null) - the way every money amount is kept. */
    public static BigDecimal round2(BigDecimal value) {
        return value == null ? null : value.setScale(2, RoundingMode.HALF_UP);
    }
}
