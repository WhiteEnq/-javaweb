package org.example.demo.common;

import javax.ws.rs.core.Response;
import javax.ws.rs.ext.ExceptionMapper;
import javax.ws.rs.ext.Provider;

import java.sql.SQLException;
import java.util.Map;

@Provider
public class ApiExceptionMapper implements ExceptionMapper<Throwable> {
    @Override
    public Response toResponse(Throwable exception) {
        if (exception instanceof ApiException apiException) {
            return Response.status(apiException.getStatus())
                    .entity(Map.of("success", false, "message", apiException.getMessage()))
                    .build();
        }
        if (exception instanceof SQLException) {
            return Response.serverError()
                    .entity(Map.of("success", false, "message", "数据库错误：" + exception.getMessage()))
                    .build();
        }
        return Response.serverError()
                .entity(Map.of("success", false, "message", "服务器错误：" + exception.getMessage()))
                .build();
    }
}
