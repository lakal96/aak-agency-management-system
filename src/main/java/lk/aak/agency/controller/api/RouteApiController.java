package lk.aak.agency.controller.api;

import jakarta.validation.Valid;
import lk.aak.agency.dto.api.RouteRequest;
import lk.aak.agency.dto.api.RouteResponse;
import lk.aak.agency.model.Route;
import lk.aak.agency.service.RouteService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.NoSuchElementException;

@RestController
@RequestMapping("/api/v1/routes")
public class RouteApiController {

    private final RouteService routeService;

    public RouteApiController(RouteService routeService) {
        this.routeService = routeService;
    }

    @GetMapping
    public List<RouteResponse> list() {
        return routeService.getAllRoutes().stream().map(RouteResponse::new).toList();
    }

    @GetMapping("/{id}")
    public RouteResponse getById(@PathVariable Long id) {
        return routeService.getRouteById(id)
                .map(RouteResponse::new)
                .orElseThrow(() -> new NoSuchElementException("Route not found."));
    }

    @PostMapping
    public ResponseEntity<RouteResponse> create(@Valid @RequestBody RouteRequest request) {
        Route route = new Route();
        applyRequest(route, request);
        return ResponseEntity.status(HttpStatus.CREATED).body(new RouteResponse(routeService.saveRoute(route)));
    }

    @PutMapping("/{id}")
    public RouteResponse update(@PathVariable Long id, @Valid @RequestBody RouteRequest request) {
        Route route = routeService.getRouteById(id)
                .orElseThrow(() -> new NoSuchElementException("Route not found."));
        applyRequest(route, request);
        return new RouteResponse(routeService.saveRoute(route));
    }

    private void applyRequest(Route route, RouteRequest request) {
        route.setRouteName(request.getRouteName());
        route.setAreaDescription(request.getAreaDescription());
        route.setStatus(request.getStatus());
    }
}
