package ru.taskflow.user.infrastructure.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import ru.taskflow.shared.exception.RateLimitExceededException;
import ru.taskflow.shared.security.JwtService;
import ru.taskflow.shared.security.TelegramInitDataValidator;
import ru.taskflow.shared.security.TelegramLoginWidgetValidator;
import ru.taskflow.user.api.IdentityProvider;
import ru.taskflow.user.api.UserDto;
import ru.taskflow.user.api.UserProfile;
import ru.taskflow.user.api.UserService;
import ru.taskflow.user.application.AuthRateLimiter;
import ru.taskflow.user.application.EmailSender;
import ru.taskflow.user.application.LoginCodeService;
import ru.taskflow.user.application.PhoneConfirmationRequest;
import ru.taskflow.user.application.PhoneInboundConfirmationService;
import ru.taskflow.user.application.PhoneVerificationProvider;
import ru.taskflow.user.application.RefreshTokenService;
import ru.taskflow.user.infrastructure.geo.CountryResolver;
import ru.taskflow.user.infrastructure.geo.TelegramLoginVisibility;
import ru.taskflow.user.infrastructure.web.dto.RequestCodeRequest;
import ru.taskflow.user.infrastructure.web.dto.RequestPhoneConfirmationRequest;
import ru.taskflow.user.infrastructure.web.dto.VerifyCodeRequest;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class AuthControllerTest {

    private static final String EMAIL = "User@Example.com";
    private static final String NORMALIZED_EMAIL = "user@example.com";
    private static final String CODE = "123456";
    private static final String PHONE = "+79991234567";

    @Mock
    private TelegramInitDataValidator initDataValidator;
    @Mock
    private TelegramLoginWidgetValidator loginWidgetValidator;
    @Mock
    private JwtService jwtService;
    @Mock
    private UserService userService;
    @Mock
    private RefreshTokenService refreshTokenService;
    @Mock
    private LoginCodeService loginCodeService;
    @Mock
    private EmailSender emailSender;
    @Mock
    private AuthRateLimiter rateLimiter;
    @Mock
    private CountryResolver countryResolver;
    @Mock
    private PhoneVerificationProvider phoneVerificationProvider;
    @Mock
    private PhoneInboundConfirmationService phoneInboundConfirmationService;

    private AuthController controller;
    private MockMvc mockMvc;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        lenient().when(rateLimiter.allow(any())).thenReturn(true);
        lenient().when(rateLimiter.allowForEmailConfirm(any(), any())).thenReturn(true);
        lenient().when(phoneVerificationProvider.isAvailable()).thenReturn(false);
        controller = new AuthController(initDataValidator, loginWidgetValidator, jwtService,
                userService, refreshTokenService, loginCodeService, emailSender, rateLimiter, new TelegramLoginVisibility(countryResolver, false),
                phoneVerificationProvider, phoneInboundConfirmationService);
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    @Test
    void requestCode_ipOverGeneralLimit_returns429WithoutCallingService() throws Exception {
        when(rateLimiter.allow(any())).thenReturn(false);

        mockMvc.perform(post("/api/v1/auth/email/request-code")
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(new RequestCodeRequest(EMAIL))))
                .andExpect(status().isTooManyRequests());

        verifyNoInteractions(loginCodeService, emailSender);
    }

    @Test
    void verifyCode_ipEmailPairOverLimit_returns429WithoutCallingService() throws Exception {
        when(rateLimiter.allowForEmailConfirm(any(), eq(EMAIL))).thenReturn(false);

        mockMvc.perform(post("/api/v1/auth/email/verify")
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(new VerifyCodeRequest(EMAIL, CODE))))
                .andExpect(status().isTooManyRequests());

        verifyNoInteractions(loginCodeService, userService);
    }

    @Test
    void requestCode_sendSucceeds_confirmsIssuedAndReturns200() throws Exception {
        when(loginCodeService.issueCode(IdentityProvider.EMAIL, EMAIL)).thenReturn(CODE);

        mockMvc.perform(post("/api/v1/auth/email/request-code")
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(new RequestCodeRequest(EMAIL))))
                .andExpect(status().isOk());

        verify(emailSender).sendLoginCode(EMAIL, CODE);
        verify(loginCodeService).confirmIssued(IdentityProvider.EMAIL, EMAIL, CODE);
        verifyNoInteractions(userService);
    }

    @Test
    void requestCode_sendFails_doesNotConfirmIssuedButStillReturns200() throws Exception {
        when(loginCodeService.issueCode(IdentityProvider.EMAIL, EMAIL)).thenReturn(CODE);
        doThrow(new org.springframework.mail.MailSendException("smtp down"))
                .when(emailSender).sendLoginCode(EMAIL, CODE);

        mockMvc.perform(post("/api/v1/auth/email/request-code")
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(new RequestCodeRequest(EMAIL))))
                .andExpect(status().isOk());

        verify(loginCodeService, never()).confirmIssued(any(), anyString(), anyString());
    }

    @Test
    void requestCode_malformedEmail_returns400() throws Exception {
        mockMvc.perform(post("/api/v1/auth/email/request-code")
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(new RequestCodeRequest("not-an-email"))))
                .andExpect(status().isBadRequest());

        verify(loginCodeService, never()).issueCode(any(), anyString());
    }

    @Test
    void requestCode_rateLimited_propagatesExceptionRatherThanSucceeding() {
        when(loginCodeService.issueCode(IdentityProvider.EMAIL, EMAIL)).thenThrow(new RateLimitExceededException("too fast"));
        HttpServletRequest httpRequest = new MockHttpServletRequest();

        assertThatThrownBy(() -> controller.requestCode(new RequestCodeRequest(EMAIL), httpRequest))
                .isInstanceOf(RateLimitExceededException.class);

        verifyNoInteractions(emailSender);
    }

    @Test
    void verifyCode_correctCode_issuesTokensAndCreatesEmailIdentity() throws Exception {
        UUID userId = UUID.randomUUID();
        var dto = new UserDto(userId, "user", null, null);
        when(loginCodeService.verifyCode(IdentityProvider.EMAIL, EMAIL, CODE)).thenReturn(true);
        when(userService.findOrCreateByIdentity(eq(IdentityProvider.EMAIL), eq(NORMALIZED_EMAIL), any(UserProfile.class)))
                .thenReturn(dto);
        when(jwtService.issueAccessToken(userId, "user")).thenReturn("access-token");
        when(refreshTokenService.issue(userId)).thenReturn("refresh-token");

        mockMvc.perform(post("/api/v1/auth/email/verify")
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(new VerifyCodeRequest(EMAIL, CODE))))
                .andExpect(status().isOk());

        verify(userService).findOrCreateByIdentity(eq(IdentityProvider.EMAIL), eq(NORMALIZED_EMAIL),
                eq(new UserProfile("user", null, null, null)));
    }

    @Test
    void verifyCode_wrongCode_returns401WithoutCreatingIdentity() throws Exception {
        when(loginCodeService.verifyCode(IdentityProvider.EMAIL, EMAIL, CODE)).thenReturn(false);

        mockMvc.perform(post("/api/v1/auth/email/verify")
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(new VerifyCodeRequest(EMAIL, CODE))))
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(userService);
    }

    @Test
    void requestPhoneConfirmation_validPhone_createsPendingAndReturnsConfirmationNumber() throws Exception {
        when(phoneVerificationProvider.requestConfirmation(PHONE))
                .thenReturn(new PhoneConfirmationRequest("79001000011", "103000"));

        mockMvc.perform(post("/api/v1/auth/phone/request-confirmation")
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(new RequestPhoneConfirmationRequest("8 (999) 123-45-67"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.confirmationNumber").value("79001000011"));

        verify(phoneInboundConfirmationService).createPending(PHONE, "79001000011", "103000", null);
    }

    @Test
    void requestPhoneConfirmation_malformedPhone_returns400WithoutCallingProvider() throws Exception {
        mockMvc.perform(post("/api/v1/auth/phone/request-confirmation")
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(new RequestPhoneConfirmationRequest("not-a-phone"))))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(phoneVerificationProvider, phoneInboundConfirmationService);
    }

    @Test
    void requestPhoneConfirmation_providerFails_returns503WithoutCreatingPending() throws Exception {
        when(phoneVerificationProvider.requestConfirmation(PHONE))
                .thenThrow(new IllegalStateException("Ucaller не принял запрос"));

        mockMvc.perform(post("/api/v1/auth/phone/request-confirmation")
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(new RequestPhoneConfirmationRequest("8 (999) 123-45-67"))))
                .andExpect(status().isServiceUnavailable());

        verifyNoInteractions(phoneInboundConfirmationService);
    }

    @Test
    void phoneConfirmationStatus_resultConfirmed_returnsTokens() throws Exception {
        when(phoneInboundConfirmationService.pollResult(PHONE))
                .thenReturn(Optional.of(new PhoneInboundConfirmationService.LoginResult("access-token", "refresh-token")));

        mockMvc.perform(get("/api/v1/auth/phone/confirmation-status").param("phone", PHONE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("confirmed"))
                .andExpect(jsonPath("$.token").value("access-token"))
                .andExpect(jsonPath("$.refreshToken").value("refresh-token"));
    }

    @Test
    void phoneConfirmationStatus_noResultButPending_returnsWaiting() throws Exception {
        when(phoneInboundConfirmationService.pollResult(PHONE)).thenReturn(Optional.empty());
        when(phoneInboundConfirmationService.hasPending(PHONE)).thenReturn(true);

        mockMvc.perform(get("/api/v1/auth/phone/confirmation-status").param("phone", PHONE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("waiting"));
    }

    @Test
    void phoneConfirmationStatus_neitherResultNorPending_returnsExpired() throws Exception {
        when(phoneInboundConfirmationService.pollResult(PHONE)).thenReturn(Optional.empty());
        when(phoneInboundConfirmationService.hasPending(PHONE)).thenReturn(false);

        mockMvc.perform(get("/api/v1/auth/phone/confirmation-status").param("phone", PHONE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("expired"));
    }

    @Test
    void cancelPhoneConfirmation_deletesPending() throws Exception {
        mockMvc.perform(post("/api/v1/auth/phone/cancel-confirmation")
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(new RequestPhoneConfirmationRequest("8 (999) 123-45-67"))))
                .andExpect(status().isOk());

        verify(phoneInboundConfirmationService).cancelPending(PHONE);
    }

    @Test
    void methods_standOverride_telegramVisibleWithoutCountryLookup() throws Exception {
        controller = new AuthController(initDataValidator, loginWidgetValidator, jwtService,
                userService, refreshTokenService, loginCodeService, emailSender, rateLimiter,
                new TelegramLoginVisibility(countryResolver, true),
                phoneVerificationProvider, phoneInboundConfirmationService);
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();

        mockMvc.perform(get("/api/v1/auth/methods"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.telegram").value(true));

        verifyNoInteractions(countryResolver);
    }

    @Test
    void methods_countryIsNotRussia_telegramAllowed() throws Exception {
        when(countryResolver.resolveCountryIso(any())).thenReturn(Optional.of("DE"));

        mockMvc.perform(get("/api/v1/auth/methods"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value(true))
                .andExpect(jsonPath("$.telegram").value(true));
    }

    @Test
    void methods_countryIsRussia_telegramHidden() throws Exception {
        when(countryResolver.resolveCountryIso(any())).thenReturn(Optional.of("RU"));

        mockMvc.perform(get("/api/v1/auth/methods"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value(true))
                .andExpect(jsonPath("$.telegram").value(false));
    }

    @Test
    void methods_countryUnknown_telegramHiddenByDefault() throws Exception {
        when(countryResolver.resolveCountryIso(any())).thenReturn(Optional.empty());

        mockMvc.perform(get("/api/v1/auth/methods"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value(true))
                .andExpect(jsonPath("$.telegram").value(false));
    }

    @Test
    void methods_phoneProviderAvailable_phoneAllowed() throws Exception {
        when(countryResolver.resolveCountryIso(any())).thenReturn(Optional.empty());
        when(phoneVerificationProvider.isAvailable()).thenReturn(true);

        mockMvc.perform(get("/api/v1/auth/methods"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.phone").value(true));
    }

    @Test
    void methods_phoneProviderUnavailable_phoneHidden() throws Exception {
        when(countryResolver.resolveCountryIso(any())).thenReturn(Optional.empty());
        when(phoneVerificationProvider.isAvailable()).thenReturn(false);

        mockMvc.perform(get("/api/v1/auth/methods"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.phone").value(false));
    }
}
