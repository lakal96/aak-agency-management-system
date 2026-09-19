package lk.aak.agency.dto.api;

import lk.aak.agency.model.Route;

public class RouteResponse {

    private final Long id;
    private final String routeName;
    private final String areaDescription;
    private final String status;

    public RouteResponse(Route route) {
        this.id = route.getId();
        this.routeName = route.getRouteName();
        this.areaDescription = route.getAreaDescription();
        this.status = route.getStatus();
    }

    public Long getId() {
        return id;
    }

    public String getRouteName() {
        return routeName;
    }

    public String getAreaDescription() {
        return areaDescription;
    }

    public String getStatus() {
        return status;
    }
}
