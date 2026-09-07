package com.hms.exception;

import com.hms.dto.ApiErrorResponse;
import com.hms.dto.ApiResponse;
import com.hms.filter.CorrelationIdFilter;
import jakarta.validation.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.HashMap;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * GlobalExceptionHandler - Centralized exception handling
 *
 * This class handles exceptions across the application to ensure:
 * 1. Consistent error response format
 * 2. No stack traces exposed to client
 * 3. Proper HTTP status codes
 *
 * @author HMS Team
 * @version Phase-1
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /**
     * The canonical error body, for handlers that have been moved onto it.
     *
     * <p>Only the 409 below uses this today. The remaining handlers still answer in the older
     * ApiResponse shape; converting them is the wider error-contract change and lands
     * separately. ApiErrorResponse is a superset of that shape -- success and error keep their
     * exact previous meaning -- so a client reading data.error cannot tell the two apart.
     */
    private static ResponseEntity<ApiErrorResponse> respond(ErrorCode code, String message) {
        return ResponseEntity.status(code.status())
                .body(ApiErrorResponse.of(code, message, CorrelationIdFilter.currentId()));
    }

    /**
     * Handle ResourceNotFoundException — 404 Not Found
     */
    @ExceptionHandler(ResourceNotFoundException.class)
    public ResponseEntity<ApiErrorResponse> handleNotFound(ResourceNotFoundException ex) {
        return respond(ErrorCode.RESOURCE_NOT_FOUND, ex.getMessage());
    }

    /**
     * Handle UnauthorizedException — 401 Unauthorized
     */
    @ExceptionHandler(UnauthorizedException.class)
    public ResponseEntity<ApiResponse<Void>> handleUnauthorized(UnauthorizedException ex) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(ApiResponse.error(ex.getMessage()));
    }

    /**
     * Handle an authenticated caller's explicit permission refusal without turning an expected
     * policy decision into the catch-all 500 response.
     */
    @ExceptionHandler(ForbiddenException.class)
    public ResponseEntity<ApiResponse<Void>> handleForbidden(ForbiddenException ex) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(ApiResponse.error(ex.getMessage()));
    }

    /**
     * Handle IllegalArgumentException — 400 Bad Request
     */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiResponse<Void>> handleIllegalArgument(IllegalArgumentException ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ApiResponse.error(ex.getMessage()));
    }

    /**
     * Handle AccessDeniedException — 403 Forbidden
     */
    @ExceptionHandler(org.springframework.security.access.AccessDeniedException.class)
    public ResponseEntity<ApiResponse<Void>> handleAccessDeniedException(
            org.springframework.security.access.AccessDeniedException ex) {
        String message = ex.getMessage() == null ? "Access Denied" : ex.getMessage();
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(ApiResponse.error(message));
    }

    /**
     * Handle Validation Exceptions (e.g. @Valid failures) — 400 Bad Request
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Object> handleValidationExceptions(MethodArgumentNotValidException ex) {
        Map<String, String> errors = ex.getBindingResult().getFieldErrors().stream()
                .collect(Collectors.toMap(
                        fe -> fe.getField(),
                        fe -> fe.getDefaultMessage(),
                        (a, b) -> a));

        Map<String, Object> body = new HashMap<>();
        body.put("errors", errors);
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(body);
    }

    /**
     * Handle ConstraintViolationException — 400 Bad Request
     */
    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<Object> handleConstraintViolation(ConstraintViolationException ex) {
        Map<String, String> errors = ex.getConstraintViolations().stream()
                .collect(Collectors.toMap(
                        cv -> cv.getPropertyPath().toString(),
                        cv -> cv.getMessage(),
                        (a, b) -> a));
        Map<String, Object> body = new HashMap<>();
        body.put("errors", errors);
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(body);
    }

    /**
     * Handle a request to a URL that maps to no handler — 404 Not Found.
     * Without this, an unknown path falls through to the catch-all below and is reported
     * as a 500 and logged at ERROR, so every typo'd URL and every drive-by scanner request
     * looks like a server crash.
     */
    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ApiResponse<Void>> handleNoResourceFound() {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(ApiResponse.error("Not found"));
    }

    /**
     * Handle a path variable or request param of the wrong type — 400 Bad Request.
     * e.g. a UUID supplied where a numeric id is declared. The caller sent a malformed
     * value, so this is a client fault, not a server fault.
     */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ApiResponse<Void>> handleTypeMismatch(MethodArgumentTypeMismatchException ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ApiResponse.error("Invalid value for '" + ex.getName() + "'"));
    }

    /**
     * Handle an unreadable/malformed request body (e.g. invalid JSON, or a JSON object
     * where an array is expected) — 400 Bad Request. The caller sent a body the endpoint
     * cannot parse, which is a client fault; without this it surfaced as a 500.
     */
    @ExceptionHandler(org.springframework.http.converter.HttpMessageNotReadableException.class)
    public ResponseEntity<ApiResponse<Void>> handleUnreadableBody() {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ApiResponse.error("Malformed or unreadable request body"));
    }

    /**
     * Handle a missing required query/form parameter — 400 Bad Request.
     * The caller omitted something required, so this is a client fault; without this it
     * fell through to the catch-all and was reported as a 500 logged at ERROR.
     */
    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<ApiResponse<Void>> handleMissingParam(MissingServletRequestParameterException ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ApiResponse.error("Missing required parameter '" + ex.getParameterName() + "'"));
    }

    /**
     * Handle a request whose HTTP method the endpoint does not support — 405 Method Not
     * Allowed. e.g. POSTing to a PUT-only route. A client using the wrong verb is not a
     * server fault; without this it was reported as a 500.
     */
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ApiResponse<Void>> handleMethodNotSupported(HttpRequestMethodNotSupportedException ex) {
        return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED)
                .body(ApiResponse.error("Method " + ex.getMethod() + " not supported for this endpoint"));
    }

    /**
     * Handle unexpected RuntimeException — 500 Internal Server Error.
     * Auth and business failures must be thrown as typed exceptions (UnauthorizedException,
     * ResourceNotFoundException, IllegalArgumentException) so they reach their specific
     * handlers above. A bare RuntimeException reaching here means something unhandled
     * broke — that is a server fault, not a client fault.
     */
    @ExceptionHandler(RuntimeException.class)
    public ResponseEntity<ApiResponse<Void>> handleRuntimeException(RuntimeException ex) {
        log.error("Unhandled RuntimeException at request boundary", ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ApiResponse.error("An unexpected error occurred. Please contact support."));
    }

    /**
     * Handle all other unhandled exceptions — 500 Internal Server Error
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleGeneralException(Exception ex) {
        log.error("Unhandled Exception at request boundary", ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ApiResponse.error("An unexpected error occurred. Please contact support."));
    }
    // -- 409 ---------------------------------------------------------------------

    /**
     * A business precondition that has changed under the caller: the bed was taken, the
     * theatre is already running another case. The message is ours, so it is shown as-is.
     *
     * <p>Without this the exception fell through to the catch-all as a 500, telling the caller
     * a server fault when the truth is that someone else got there first.
     */
    @ExceptionHandler(ConflictException.class)
    public ResponseEntity<ApiErrorResponse> handleConflict(ConflictException ex) {
        return respond(ErrorCode.CONFLICT, ex.getMessage());
    }

    /**
     * A database constraint rejected an otherwise well-formed request. Constraint names and SQL
     * fragments are deployment details, so keep them out of the public response while preserving
     * the retryable conflict semantics callers already use for business preconditions.
     */
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ApiErrorResponse> handleDataIntegrityViolation(DataIntegrityViolationException ex) {
        log.warn("Database constraint rejected request: {}", ex.getMostSpecificCause().getClass().getSimpleName());
        return respond(ErrorCode.CONFLICT, "The requested change conflicts with existing data");
    }

}
