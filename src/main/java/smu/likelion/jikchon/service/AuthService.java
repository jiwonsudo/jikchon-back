package smu.likelion.jikchon.service;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.json.JSONObject;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.authentication.builders.AuthenticationManagerBuilder;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import smu.likelion.jikchon.domain.member.JwtRefreshToken;
import smu.likelion.jikchon.domain.member.Member;
import smu.likelion.jikchon.domain.member.VerifiedMember;
import smu.likelion.jikchon.dto.member.MemberRequestDto;
import smu.likelion.jikchon.dto.member.MemberResponseDto;
import smu.likelion.jikchon.dto.member.TokenResponseDto;
import smu.likelion.jikchon.exception.*;
import smu.likelion.jikchon.jwt.TokenProvider;
import smu.likelion.jikchon.repository.JwtRefreshTokenRepository;
import smu.likelion.jikchon.repository.MemberRepository;
import smu.likelion.jikchon.repository.VerifiedCacheRepository;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class AuthService implements UserDetailsService {
    private final MemberRepository memberRepository;
    private final VerifiedCacheRepository verifiedCacheRepository;
    private final JwtRefreshTokenRepository jwtRefreshTokenRepository;
    private final TokenProvider tokenProvider;
    private final PasswordEncoder passwordEncoder;
    private final AuthenticationManagerBuilder authenticationManagerBuilder;


    @Transactional
    public MemberResponseDto.Simple signUpCustomer(MemberRequestDto.SignUp memberRequestDto) {
        checkDuplicatePhoneNumber(memberRequestDto.getPhoneNumber());
        Member member = memberRequestDto.toCustomerEntity();
        member.encodePassword(passwordEncoder);

        return MemberResponseDto.Simple.of(memberRepository.save(member));
    }

    @Transactional
    public MemberResponseDto.Simple signUpSeller(MemberRequestDto.SignUp memberRequestDto) {
        checkDuplicatePhoneNumber(memberRequestDto.getPhoneNumber());
        isVerified(memberRequestDto);

        Member member = memberRequestDto.toSellerEntity();
        member.encodePassword(passwordEncoder);

        return MemberResponseDto.Simple.of(memberRepository.save(member));

    }

    public void isVerified(MemberRequestDto.SignUp memberRequestDto) {
        Optional<VerifiedMember> verifiedMemberOptional = verifiedCacheRepository.findByPhoneNumber(memberRequestDto.getPhoneNumber());

        if (verifiedMemberOptional.isPresent()) {
            VerifiedMember verifiedMember = verifiedMemberOptional.get();
            if (!Objects.equals(memberRequestDto.getCompanyNumber(), verifiedMember.getCompanyNumber())) {
                throw new CustomBadRequestException(ErrorCode.NOT_VERIFIED_COMPANY_NUMBER);
            }
            verifiedCacheRepository.delete(verifiedMember);
        } else {
            throw new CustomBadRequestException(ErrorCode.NOT_VERIFIED_COMPANY_NUMBER);
        }
    }

    @Transactional(readOnly = true)
    public void checkDuplicatePhoneNumber(String phoneNumber) {
        memberRepository.findByPhoneNumber(phoneNumber)
                .ifPresent(member -> {
                    throw new CustomBadRequestException(ErrorCode.DUPLICATE_PHONE_NUMBER);
                });
    }


    public void verifyCompanyNumber(MemberRequestDto.VerifyCompanyNumber verifyCompanyNumberRequest) {
        final String VERIFIED_STATUS_CODE = "01";
        final String requestUrl = "https://api.odcloud.kr/api/nts-businessman/v1/status?" +
                "serviceKey=bFcIfbKjGI8rVFG9xZouBt%2B3s0kITpf0u6Loz8ekrvseXj%2Bye16tUmvGrBgLdK5zbVA3cAanmNPa%2F1o%2B2n2feQ%3D%3D";

        checkDuplicatePhoneNumber(verifyCompanyNumberRequest.getPhoneNumber());

        verifiedCacheRepository.findByPhoneNumber(verifyCompanyNumberRequest.getPhoneNumber())
                .ifPresent(verifiedCacheRepository::delete);

        JSONObject requestBody = new JSONObject();
        requestBody.put("b_no", new String[]{verifyCompanyNumberRequest.getCompanyNumber()});

        JSONObject responseJson = callApiAndGetResponse(requestUrl, requestBody.toString());

        if (!getBusinessStatus(responseJson).equals(VERIFIED_STATUS_CODE)) {
            throw new CustomBadRequestException(ErrorCode.BAD_REQUEST);
        }

        verifiedCacheRepository.save(VerifiedMember.builder()
                .phoneNumber(verifyCompanyNumberRequest.getPhoneNumber())
                .companyNumber(verifyCompanyNumberRequest.getCompanyNumber())
                .build());
    }

    private JSONObject callApiAndGetResponse(String requestUrl, String requestBody) {
        HttpURLConnection urlConnection = null;
        BufferedReader bufferedReader = null;
        BufferedWriter bufferedWriter = null;

        try {
            URL url = new URL(requestUrl);
            urlConnection = (HttpURLConnection) url.openConnection();
            urlConnection.setRequestMethod("POST");
            urlConnection.setRequestProperty("Content-type", "application/json");
            urlConnection.setDoOutput(true);

            OutputStream outputStream = urlConnection.getOutputStream();

            bufferedWriter = new BufferedWriter(new OutputStreamWriter(outputStream, StandardCharsets.UTF_8));
            bufferedWriter.write(requestBody);
            bufferedWriter.flush();
            bufferedWriter.close();

            StringBuilder responseBuilder = new StringBuilder();
            if (urlConnection.getResponseCode() == HttpURLConnection.HTTP_OK) {
                bufferedReader = new BufferedReader(new InputStreamReader(urlConnection.getInputStream(), StandardCharsets.UTF_8));

                String line;
                while ((line = bufferedReader.readLine()) != null) {
                    responseBuilder.append(line);
                }
            } else {
                throw new CustomInternalServerErrorException(ErrorCode.INTERNAL_SERVER_ERROR);
            }
            return new JSONObject(responseBuilder.toString());
        } catch (IOException e) {
            throw new CustomInternalServerErrorException(ErrorCode.OPEN_API_ERROR);
        } finally {
            if (bufferedReader != null) {
                try {
                    bufferedReader.close();
                } catch (IOException ignored) {
                }
            }
            if (bufferedWriter != null) {
                try {
                    bufferedWriter.close();
                } catch (IOException ignored) {
                }
            }
            if (urlConnection != null) {
                urlConnection.disconnect();
            }
        }
    }

    private String getBusinessStatus(JSONObject apiResponseJson) {
        return apiResponseJson.getJSONArray("data").getJSONObject(0).getString("b_stt_cd");
    }

    @Transactional
    public TokenResponseDto.AccessToken login(HttpServletResponse response, MemberRequestDto.Login loginRequestDto) {
        Authentication authenticationToken = new UsernamePasswordAuthenticationToken(
                loginRequestDto.getPhoneNumber(), loginRequestDto.getPassword()
        );

        Authentication authentication = authenticationManagerBuilder.getObject().authenticate(authenticationToken);
        SecurityContextHolder.getContext().setAuthentication(authentication);

        TokenResponseDto.FullInfo fullTokenInfo = tokenProvider.generateTokenResponse(authentication);

        Long memberId = Long.parseLong(authentication.getName());
        createOrUpdateRefreshToken(memberId, fullTokenInfo);

        setRefreshTokenCookie(response, fullTokenInfo.getRefreshToken());

        return TokenResponseDto.AccessToken.of(fullTokenInfo);
    }

    private void createOrUpdateRefreshToken(Long memberId, TokenResponseDto.FullInfo tokenResponseDto) {
        JwtRefreshToken refreshToken = jwtRefreshTokenRepository.findByMemberId(memberId)
                .orElse(JwtRefreshToken.builder().
                        member(Member.builder().id(memberId).build())
                        .build());

        refreshToken.updateRefreshToken(tokenResponseDto);
        jwtRefreshTokenRepository.save(refreshToken);
    }

    private void setRefreshTokenCookie(HttpServletResponse response, String refreshToken) {

        refreshToken = "Bearer " + refreshToken;
        refreshToken = URLEncoder.encode(refreshToken, StandardCharsets.UTF_8);

        Cookie cookie = new Cookie("REFRESH_TOKEN", refreshToken);
        cookie.setPath("/");
        cookie.setHttpOnly(true);
        cookie.setDomain("localhost");
        cookie.setMaxAge(24 * 60 * 60);
        response.addCookie(cookie);
    }

    @Transactional
    public void logout(HttpServletRequest request) {
        String refreshToken = tokenProvider.getRefreshToken(request);
        JwtRefreshToken jwtRefreshToken = jwtRefreshTokenRepository.findByRefreshToken(refreshToken)
                .orElseThrow(() ->
                        new CustomUnauthorizedException(ErrorCode.REFRESH_TOKEN_NOT_EXIST));

        jwtRefreshTokenRepository.delete(jwtRefreshToken);
    }

    @Transactional(readOnly = true)
    public TokenResponseDto.AccessToken refreshAccessToken(HttpServletRequest request) {
        String refreshToken = tokenProvider.getRefreshToken(request);
        tokenProvider.validateAccessToken(refreshToken);

        Member member = memberRepository.findByRefreshToken(refreshToken).orElseThrow(() ->
                new CustomUnauthorizedException(ErrorCode.INVALID_TOKEN)
        );

        return tokenProvider.generateTokenResponse(member);
    }

    @Override
    public UserDetails loadUserByUsername(String phoneNumber) throws UsernameNotFoundException {
        Member member = memberRepository.findByPhoneNumber(phoneNumber).orElseThrow(() ->
                new CustomNotFoundException(ErrorCode.NOT_FOUND)
        );

        return User.builder()
                .username(member.getId().toString())
                .password(member.getPassword())
                .authorities(member.getAuthority())
                .build();
    }
}
