package com.example.planner.integration;

import com.example.planner.controller.AuthController;
import com.example.planner.controller.AiPlanController;
import com.example.planner.controller.PlanController;
import com.example.planner.ai.GeneratedPlanService;
import com.example.planner.ai.PlanDraftService;
import com.example.planner.model.AppUser;
import com.example.planner.repository.UserRepository;
import com.example.planner.security.PasswordConfig;
import com.example.planner.security.PlannerPrincipal;
import com.example.planner.security.PlannerUserDetailsService;
import com.example.planner.security.SecurityConfig;
import com.example.planner.service.RegistrationService;
import com.example.planner.service.PlanService;
import com.example.planner.service.PlanTreeService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.junit.jupiter.web.SpringJUnitWebConfig;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.thymeleaf.spring6.SpringTemplateEngine;
import org.thymeleaf.spring6.view.ThymeleafViewResolver;
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver;
import tools.jackson.databind.json.JsonMapper;

import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.regex.Pattern;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.response.SecurityMockMvcResultMatchers.authenticated;
import static org.springframework.security.test.web.servlet.response.SecurityMockMvcResultMatchers.unauthenticated;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringJUnitWebConfig(AuthSecurityIntegrationTest.Config.class)
class AuthSecurityIntegrationTest {
    private static final Pattern CSRF_FIELD = Pattern.compile("name=\"_csrf\"\\s+value=\"([^\"]+)\"");
    private static final String DRAFT_JSON = """
            {"title":"<b>Reading plan</b>","goal":null,"startDate":null,"endDate":null,"subPlans":[],"tasks":[
              {"title":"Read chapter","description":null,"startDate":null,"endDate":null,"subTasks":[
                {"title":"Read page one","description":null,"scheduledDate":null}
              ]}
            ]}
            """;
    @TestConfiguration
    @EnableWebMvc
    @ComponentScan(basePackageClasses = AuthController.class, useDefaultFilters = false,
            includeFilters = @ComponentScan.Filter(type = FilterType.ANNOTATION, classes = ControllerAdvice.class))
    @Import({SecurityConfig.class, PasswordConfig.class, PlannerUserDetailsService.class,
            RegistrationService.class, AuthController.class, AiPlanController.class, PlanController.class})
    static class Config {
        @Bean
        UserRepository users() {
            return mock(UserRepository.class);
        }

        @Bean
        PlanDraftService planDrafts() {
            return new PlanDraftService((instructions, input) -> DRAFT_JSON, new JsonMapper());
        }

        @Bean
        GeneratedPlanService generatedPlans() {
            return mock(GeneratedPlanService.class);
        }

        @Bean
        PlanService plans() {
            return mock(PlanService.class);
        }

        @Bean
        PlanTreeService trees() {
            return mock(PlanTreeService.class);
        }

        @Bean
        ThymeleafViewResolver viewResolver() {
            var templates = new ClassLoaderTemplateResolver();
            templates.setPrefix("templates/");
            templates.setSuffix(".html");
            templates.setTemplateMode("HTML");
            templates.setCharacterEncoding("UTF-8");
            var engine = new SpringTemplateEngine();
            engine.setTemplateResolver(templates);
            var views = new ThymeleafViewResolver();
            views.setTemplateEngine(engine);
            views.setCharacterEncoding("UTF-8");
            return views;
        }
    }

    @Autowired WebApplicationContext context;
    @Autowired UserRepository users;
    @Autowired PasswordEncoder passwords;
    @Autowired GeneratedPlanService generatedPlans;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        reset(users);
        reset(generatedPlans);
        when(users.findByUsername("alice")).thenReturn(Optional.of(
                new AppUser(42, "alice", passwords.encode(" sample password "), OffsetDateTime.now())));
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    @Test
    void loginRestoresPrivateGetRotatesSessionAndLogoutInvalidatesIt() throws Exception {
        var guest = mvc.perform(get("/plans/42"))
                .andExpect(status().is3xxRedirection()).andReturn();
        var session = (MockHttpSession) guest.getRequest().getSession(false);
        assertNotNull(session);
        String guestId = session.getId();

        mvc.perform(post("/login").session(session).with(csrf())
                        .param("username", " ALICE ").param("password", " sample password "))
                .andExpect(redirectedUrl("/plans/42"))
                .andExpect(authenticated().withUsername("alice"));
        assertNotEquals(guestId, session.getId());

        mvc.perform(post("/logout").session(session).with(csrf()))
                .andExpect(redirectedUrl("/login")).andExpect(unauthenticated());
        assertTrue(session.isInvalid());
    }

    @Test
    void loginRejectsTrimmedPasswordAndDefaultsToPlansOnSuccess() throws Exception {
        mvc.perform(post("/login").with(csrf()).param("username", "alice").param("password", "sample password"))
                .andExpect(redirectedUrl("/login?error")).andExpect(unauthenticated());
        mvc.perform(post("/login").with(csrf()).param("username", "alice").param("password", " sample password "))
                .andExpect(redirectedUrl("/plans")).andExpect(authenticated().withUsername("alice"));
    }

    @Test
    void requiresCsrfAndRejectsNonPlannerPrincipalsOnPrivateRoutes() throws Exception {
        for (String path : new String[]{"/register", "/login", "/logout"}) {
            mvc.perform(post(path)).andExpect(status().isForbidden());
            mvc.perform(post(path).with(csrf().useInvalidToken())).andExpect(status().isForbidden());
        }
        for (String path : new String[]{"/plans", "/subplans", "/tasks", "/subtasks", "/ai/plans/new"}) {
            mvc.perform(get(path).with(user("alice"))).andExpect(status().isForbidden());
            mvc.perform(post(path).with(user("alice")).with(csrf())).andExpect(status().isForbidden());
        }
        verifyNoInteractions(users);
    }

    @Test
    void authenticatedPlannerPrincipalCanReachPlannerAndAiRoutes() throws Exception {
        var principal = new PlannerPrincipal(42, "alice", "");
        mvc.perform(get("/plans").with(user(principal))).andExpect(status().isOk());
        mvc.perform(get("/ai/plans/new").with(user(principal))).andExpect(status().isOk());
        mvc.perform(get("/ai/plans/new")).andExpect(status().is3xxRedirection());
    }

    @Test
    void registerUsesRenderedTokenAndReturnsOnlyUsernameOnValidationErrors() throws Exception {
        var form = mvc.perform(get("/register"))
                .andExpect(status().isOk()).andExpect(view().name("auth/register")).andReturn();
        var session = (MockHttpSession) form.getRequest().getSession(false);
        assertNotNull(session);
        String token = formToken(form.getResponse().getContentAsString());
        String password = "keep this password private";
        String confirmation = "different password value";
        mvc.perform(post("/register").session(session).param("_csrf", token)
                        .param("username", " Alice_NEW ").param("password", password)
                        .param("confirmPassword", confirmation))
                .andExpect(status().isOk())
                .andExpect(view().name("auth/register"))
                .andExpect(model().attribute("form", java.util.Map.of("username", " Alice_NEW ")))
                .andExpect(content().string(containsString("value=\" Alice_NEW \"")))
                .andExpect(content().string(not(containsString(password))))
                .andExpect(content().string(not(containsString(confirmation))));
        verifyNoInteractions(users);

        when(users.insert(eq("alice_new"), anyString())).thenReturn(true);
        mvc.perform(post("/register").session(session).param("_csrf", token)
                        .param("username", " Alice_NEW ").param("password", password)
                        .param("confirmPassword", password))
                .andExpect(redirectedUrl("/login"))
                .andExpect(unauthenticated());
        var hash = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(users).insert(eq("alice_new"), hash.capture());
        assertTrue(passwords.matches(password, hash.getValue()));
    }

    @Test
    void successfulLoginInvalidatesRenderedTokenFromGuestSession() throws Exception {
        var form = mvc.perform(get("/login")).andExpect(status().isOk()).andReturn();
        var session = (MockHttpSession) form.getRequest().getSession(false);
        assertNotNull(session);
        String guestToken = formToken(form.getResponse().getContentAsString());
        mvc.perform(post("/login").session(session).param("_csrf", guestToken)
                        .param("username", "alice").param("password", " sample password "))
                .andExpect(redirectedUrl("/plans")).andExpect(authenticated());
        mvc.perform(post("/logout").session(session).param("_csrf", guestToken))
                .andExpect(status().isForbidden()).andExpect(authenticated());
        var signedIn = mvc.perform(get("/login").session(session)).andExpect(status().isOk()).andReturn();
        var newToken = formToken(signedIn.getResponse().getContentAsString());
        mvc.perform(post("/logout").session(session).param("_csrf", newToken))
                .andExpect(redirectedUrl("/login")).andExpect(unauthenticated());
    }

    private static String formToken(String html) {
        var field = CSRF_FIELD.matcher(html);
        assertTrue(field.find(), "Expected Thymeleaf to render the session CSRF field");
        return field.group(1);
    }

    private static String postFormToken(String html, String action) {
        var form = Pattern.compile("<form\\b(?=[^>]*\\bmethod=\"post\")(?=[^>]*\\baction=\""
                + Pattern.quote(action) + "\")[^>]*>(.*?)</form>", Pattern.DOTALL).matcher(html);
        assertTrue(form.find(), "Expected POST form for " + action);
        String token = formToken(form.group(1));
        assertFalse(form.find(), "Expected one POST form for " + action);
        return token;
    }

    @Test
    void sharedPlannerHeaderRendersAccountAndWorkingLogoutOutsideWorkspacePanels() throws Exception {
        var login = mvc.perform(get("/login")).andExpect(status().isOk()).andReturn();
        var session = (MockHttpSession) login.getRequest().getSession(false);
        mvc.perform(post("/login").session(session)
                        .param("_csrf", formToken(login.getResponse().getContentAsString()))
                        .param("username", "alice").param("password", " sample password "))
                .andExpect(redirectedUrl("/plans")).andExpect(authenticated());

        String logoutToken = null;
        for (String path : new String[]{"/plans", "/plans/new", "/ai/plans/new"}) {
            var page = mvc.perform(get(path).session(session)).andExpect(status().isOk()).andReturn();
            String html = page.getResponse().getContentAsString();
            var header = Pattern.compile("<header class=\"app-header\">(.*?)</header>", Pattern.DOTALL)
                    .matcher(html);
            assertTrue(header.find(), path);
            assertTrue(header.group(1).contains("class=\"account-name\">alice</span>"), path);
            logoutToken = postFormToken(header.group(1), "/logout");
            postFormToken(html, "/logout");
        }

        for (String path : new String[]{"/login", "/register"}) {
            mvc.perform(get(path).session(session)).andExpect(status().isOk())
                    .andExpect(content().string(not(containsString("action=\"/logout\""))));
            mvc.perform(get(path)).andExpect(status().isOk())
                    .andExpect(content().string(not(containsString("action=\"/logout\""))));
        }

        mvc.perform(post("/logout").session(session)).andExpect(status().isForbidden());
        mvc.perform(post("/logout").session(session).param("_csrf", logoutToken))
                .andExpect(redirectedUrl("/login")).andExpect(unauthenticated());
        assertTrue(session.isInvalid());
    }

    @Test
    void aiFormsRenderAndUseRealCsrfWithOneConfirmationPerSessionDraft() throws Exception {
        var alice = new PlannerPrincipal(42, "alice", "");
        var bob = new PlannerPrincipal(43, "bob", "");
        var form = mvc.perform(get("/ai/plans/new").with(user(alice))).andExpect(status().isOk())
                .andExpect(view().name("ai/form")).andReturn();
        var session = (MockHttpSession) form.getRequest().getSession(false);
        assertNotNull(session);
        var preview = mvc.perform(post("/ai/plans/preview").session(session).with(user(alice))
                        .param("_csrf", postFormToken(form.getResponse().getContentAsString(), "/ai/plans/preview"))
                        .param("goal", "Read").param("level", "Beginner").param("durationDays", "7"))
                .andExpect(status().isOk()).andExpect(view().name("ai/preview"))
                .andExpect(model().attribute("draftTitle", "<b>Reading plan</b>"))
                .andExpect(content().string(containsString("name=\"draftId\"")))
                .andExpect(content().string(containsString("name=\"json\"")))
                .andExpect(content().string(containsString("&lt;b&gt;Reading plan&lt;/b&gt;")))
                .andReturn();
        String firstId = preview.getModelAndView().getModel().get("draftId").toString();
        postFormToken(preview.getResponse().getContentAsString(), "/logout");
        String previewToken = postFormToken(preview.getResponse().getContentAsString(), "/ai/plans/edit");
        var edited = mvc.perform(post("/ai/plans/edit").session(session).with(user(alice))
                        .param("_csrf", previewToken).param("draftId", firstId)
                        .param("json", DRAFT_JSON.replace("<b>Reading plan</b>", "Edited plan")))
                .andExpect(status().isOk()).andExpect(content().string(containsString("Edited plan"))).andReturn();
        String draftId = edited.getModelAndView().getModel().get("draftId").toString();
        assertNotEquals(firstId, draftId);
        String editedToken = postFormToken(edited.getResponse().getContentAsString(), "/ai/plans/confirm");

        mvc.perform(post("/ai/plans/confirm").session(session).with(user(alice)).param("draftId", draftId))
                .andExpect(status().isForbidden());
        mvc.perform(post("/ai/plans/confirm").session(session).with(user(bob))
                        .param("_csrf", editedToken).param("draftId", draftId))
                .andExpect(status().isNotFound());
        verifyNoInteractions(generatedPlans);

        String validatedJson = (String) edited.getModelAndView().getModel().get("draftJson");
        when(generatedPlans.confirm(42, validatedJson)).thenReturn(12L);
        mvc.perform(post("/ai/plans/confirm").session(session).with(user(alice))
                        .param("_csrf", editedToken).param("draftId", draftId))
                .andExpect(redirectedUrl("/plans/12"));
        mvc.perform(post("/ai/plans/confirm").session(session).with(user(alice))
                        .param("_csrf", editedToken).param("draftId", draftId))
                .andExpect(status().isNotFound());
        verify(generatedPlans).confirm(42, validatedJson);
    }
}
