package com.example.fluxio.transfer.api;

import com.example.fluxio.transfer.application.InvalidPartRequestException;
import com.example.fluxio.transfer.application.InvalidUploadStateException;
import com.example.fluxio.transfer.application.UploadExpiredException;
import com.example.fluxio.transfer.application.UploadNotFoundException;
import com.example.fluxio.transfer.application.InvalidCompletionException;
import com.example.fluxio.transfer.persistence.InvalidUploadSizeException;
import com.example.fluxio.transfer.persistence.QuotaExceededException;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public final class TransferProblemHandler {
    @ExceptionHandler(TransferContractException.class)
    ResponseEntity<ProblemDetail> handleContract(TransferContractException exception) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(exception.status(), exception.getMessage());
        problem.setTitle(exception.status().getReasonPhrase());
        problem.setProperty("code", exception.code());
        return ResponseEntity.status(exception.status())
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(problem);
    }

    @ExceptionHandler(InvalidPartRequestException.class)
    ResponseEntity<ProblemDetail> invalidPartRequest() {
        return problem(org.springframework.http.HttpStatus.BAD_REQUEST,
                "INVALID_PART_REQUEST", "Part numbers must be unique, in range, and limited to 32");
    }

    @ExceptionHandler(UploadNotFoundException.class)
    ResponseEntity<ProblemDetail> uploadNotFound() {
        return problem(org.springframework.http.HttpStatus.NOT_FOUND,
                "UPLOAD_NOT_FOUND", "Upload was not found");
    }

    @ExceptionHandler(InvalidUploadStateException.class)
    ResponseEntity<ProblemDetail> invalidUploadState() {
        return problem(org.springframework.http.HttpStatus.CONFLICT,
                "INVALID_UPLOAD_STATE", "Operation is not allowed in the current upload state");
    }

    @ExceptionHandler(UploadExpiredException.class)
    ResponseEntity<ProblemDetail> uploadExpired() {
        return problem(org.springframework.http.HttpStatus.GONE,
                "UPLOAD_EXPIRED", "Upload session has expired");
    }

    @ExceptionHandler(InvalidCompletionException.class)
    ResponseEntity<ProblemDetail> invalidCompletion() {
        return problem(org.springframework.http.HttpStatus.BAD_REQUEST,
                "INVALID_COMPLETION", "Completion parts do not match the authoritative uploaded parts");
    }

    @ExceptionHandler(InvalidUploadSizeException.class)
    ResponseEntity<ProblemDetail> invalidUploadSize() {
        return problem(org.springframework.http.HttpStatus.PAYLOAD_TOO_LARGE,
                "FILE_TOO_LARGE", "Declared file size exceeds 5 GiB");
    }

    @ExceptionHandler(QuotaExceededException.class)
    ResponseEntity<ProblemDetail> quotaExceeded() {
        return problem(org.springframework.http.HttpStatus.UNPROCESSABLE_ENTITY,
                "QUOTA_EXCEEDED", "Account storage quota would be exceeded");
    }

    private ResponseEntity<ProblemDetail> problem(
            org.springframework.http.HttpStatus status, String code, String detail) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setTitle(status.getReasonPhrase());
        problem.setProperty("code", code);
        return ResponseEntity.status(status)
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(problem);
    }
}
