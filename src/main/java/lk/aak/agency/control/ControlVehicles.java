package lk.aak.agency.control;

import java.util.List;

/** The agency's lorries as they are named in Connect Plus. MAKOLARTM-AV4 is the van-sales lorry: it sells on the road and keeps its stock on board. */
public final class ControlVehicles {

    public record Vehicle(String code, boolean vanSales) { }

    public static final List<Vehicle> ALL = List.of(
            new Vehicle("MAKOLARTM-A", false),
            new Vehicle("MAKOLARTM-AV2", false),
            new Vehicle("MAKOLARTM-AV3", false),
            new Vehicle("MAKOLARTM-AV4", true),
            new Vehicle("SMMTMAKOLA-A", false));

    private ControlVehicles() { }

    public static Vehicle find(String code) {
        if (code == null) throw new IllegalArgumentException("Choose a lorry.");
        return ALL.stream().filter(v -> v.code().equalsIgnoreCase(code.trim())).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown lorry \"" + code + "\"."));
    }
}
