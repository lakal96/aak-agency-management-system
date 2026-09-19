package lk.aak.agency.dto.api;

import jakarta.validation.constraints.NotBlank;

public class RouteRequest {

    @NotBlank(message = "Route name is required.")
    private String routeName;

    private String areaDescription;
    private String status;

    public String getRouteName() {
        return routeName;
    }

    public void setRouteName(String routeName) {
        this.routeName = routeName;
    }

    public String getAreaDescription() {
        return areaDescription;
    }

    public void setAreaDescription(String areaDescription) {
        this.areaDescription = areaDescription;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }
}
