package com.consense.web;

import com.consense.ai.LlmProfiles;
import com.consense.common.ApiResponse;
import com.consense.common.JsonUtils;
import javax.servlet.*;
import javax.servlet.http.*;
import java.io.IOException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component @RequiredArgsConstructor
public class LlmProfileFilter extends OncePerRequestFilter {
    private final LlmProfiles profiles;
    @Override protected void doFilterInternal(HttpServletRequest request,HttpServletResponse response,FilterChain chain)throws ServletException,IOException {
        String id=request.getHeader(LlmProfiles.HEADER);
        if(id!=null&&!profiles.supported(id)) {
            response.setStatus(400);response.setContentType("application/json");response.setCharacterEncoding("UTF-8");
            response.getWriter().write(JsonUtils.write(ApiResponse.fail(400,"Unknown LLM profile. Choose local or minimax-cn.")));return;
        }
        try(LlmProfiles.Scope ignored=profiles.bind(profiles.resolve(id))){chain.doFilter(request,response);}
    }
}
