package com.example.planner.service;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

final class OwnerScope {
    private OwnerScope() {}

    static void require(long ownerId) {
        if (ownerId <= 0) {
            throw required();
        }
    }

    static ResponseStatusException required() {
        return new ResponseStatusException(HttpStatus.FORBIDDEN, "Yêu cầu tài khoản xác thực");
    }
}
