// GlobalResponseAdvice —— 把每个成功返回的 @RestController 值包成 ApiResponse 响应封装，
// 但对那些包装会造成错误或冗余的场景（ApiResponse、ResponseEntity、String、void）予以放行。
// 与 GlobalExceptionHandler 配合，后者会对错误路径做同样的包装。
package com.speakcoach.controller.advice;

import com.speakcoach.common.ApiResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.MethodParameter;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyAdvice;

/**
 * {@link ResponseBodyAdvice} 实现，把返回值包装进
 * {@link ApiResponse#success(Object)}，让 controller 不必手动包装。
 *
 * <p>对以下情况予以放行：</p>
 * <ul>
 *   <li>{@link ApiResponse} —— 本身已是封装。</li>
 *   <li>{@link ResponseEntity} —— 由调用方控制 status/headers。</li>
 *   <li>{@link CharSequence} —— {@code StringHttpMessageConverter} 必须原样工作，
 *   否则响应体会被加上 JSON 引号。</li>
 *   <li>{@code void} / {@code null} —— 包装成空的成功响应。</li>
 * </ul>
 */
@RestControllerAdvice
@Slf4j
public class GlobalResponseAdvice implements ResponseBodyAdvice<Object> {

    @Override
    public boolean supports(MethodParameter returnType,
                            Class<? extends HttpMessageConverter<?>> converterType) {
        if (returnType == null) {
            return false;
        }
        Class<?> parameterType = returnType.getParameterType();
        if (parameterType == null) {
            return false;
        }
        // 若声明的返回类型属于下面的放行情形之一，则跳过包装。
        return !(ApiResponse.class.isAssignableFrom(parameterType)
                || ResponseEntity.class.isAssignableFrom(parameterType)
                || CharSequence.class.isAssignableFrom(parameterType)
                || parameterType == void.class
                || parameterType == Void.class);
    }

    @Override
    public Object beforeBodyWrite(Object body,
                                  MethodParameter returnType,
                                  MediaType selectedContentType,
                                  Class<? extends HttpMessageConverter<?>> selectedConverterType,
                                  ServerHttpRequest request,
                                  ServerHttpResponse response) {
        if (body == null) {
            return ApiResponse.success();
        }
        if (body instanceof ApiResponse) {
            return body;
        }
        if (body instanceof ResponseEntity) {
            return body;
        }
        if (body instanceof CharSequence) {
            return body;
        }
        return ApiResponse.success(body);
    }
}
