package com.itmo.napoleonit.aiinterviewer.web

import com.itmo.napoleonit.aiinterviewer.stub.StubService
import com.itmo.napoleonit.aiinterviewer.web.dto.*
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.http.HttpStatus
import org.springframework.security.authentication.AuthenticationManager
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.web.context.SecurityContextRepository
import org.springframework.web.bind.annotation.*
import java.security.Principal
import java.util.UUID

@RestController
@RequestMapping("/api/auth")
class AuthController(
    private val authManager: AuthenticationManager,
    private val contextRepository: SecurityContextRepository,
) {
    @PostMapping("/login")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun login(@RequestBody body: LoginRequest, request: HttpServletRequest, response: HttpServletResponse) {
        val auth = runCatching {
            authManager.authenticate(UsernamePasswordAuthenticationToken(body.username, body.password))
        }.getOrElse { throw ApiException(HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED", "Неверный логин или пароль") }

        val context = SecurityContextHolder.createEmptyContext().apply { authentication = auth }
        SecurityContextHolder.setContext(context)
        contextRepository.saveContext(context, request, response)
    }

    @PostMapping("/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun logout(request: HttpServletRequest) {
        request.getSession(false)?.invalidate()
        SecurityContextHolder.clearContext()
    }

    @GetMapping("/me")
    fun me(principal: Principal): CurrentUser = CurrentUser(principal.name, principal.name.replaceFirstChar { it.uppercase() })
}

@RestController
@RequestMapping("/api/vacancies")
class VacancyController(private val service: StubService) {

    @GetMapping
    fun list(principal: Principal): List<VacancyListItem> = service.listVacancies(principal.name)

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    fun create(principal: Principal, @RequestBody input: VacancyInput): Vacancy =
        service.createVacancy(principal.name, input)

    @GetMapping("/{id}")
    fun get(principal: Principal, @PathVariable id: UUID): Vacancy = service.getVacancy(principal.name, id)

    @PutMapping("/{id}")
    fun update(principal: Principal, @PathVariable id: UUID, @RequestBody input: VacancyInput): Vacancy =
        service.updateVacancy(principal.name, id, input)

    @GetMapping("/{id}/question-sets")
    fun questionSets(principal: Principal, @PathVariable id: UUID): List<QuestionSet> =
        service.listQuestionSets(principal.name, id)

    @PostMapping("/{id}/question-sets")
    @ResponseStatus(HttpStatus.CREATED)
    fun generate(
        principal: Principal,
        @PathVariable id: UUID,
        @RequestBody(required = false) body: GenerateQuestionsRequest?,
    ): QuestionSet = service.generateQuestionSet(principal.name, id, body?.source ?: QuestionSetSource.LLM)
}

@RestController
@RequestMapping("/api/question-sets")
class QuestionSetController(private val service: StubService) {

    @GetMapping("/{id}")
    fun get(principal: Principal, @PathVariable id: UUID): QuestionSet = service.getQuestionSet(principal.name, id)

    @PutMapping("/{id}")
    fun update(
        principal: Principal,
        @PathVariable id: UUID,
        @RequestBody body: UpdateQuestionsRequest,
    ): QuestionSet = service.updateQuestionSet(principal.name, id, body.questions)

    @PostMapping("/{id}/revise")
    @ResponseStatus(HttpStatus.CREATED)
    fun revise(principal: Principal, @PathVariable id: UUID): QuestionSet =
        service.reviseQuestionSet(principal.name, id)

    @PostMapping("/{id}/freeze")
    fun freeze(principal: Principal, @PathVariable id: UUID): QuestionSet =
        service.freezeQuestionSet(principal.name, id)
}

@RestController
@RequestMapping("/api/interviews")
class InterviewController(private val service: StubService) {

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    fun create(principal: Principal, @RequestBody input: InterviewInput): InterviewDetail =
        service.createInterview(principal.name, input)

    @GetMapping
    fun list(principal: Principal, @RequestParam(required = false) vacancyId: UUID?): List<InterviewListItem> =
        service.listInterviews(principal.name, vacancyId)

    @GetMapping("/{id}")
    fun get(principal: Principal, @PathVariable id: UUID): InterviewDetail = service.getInterview(principal.name, id)

    @GetMapping("/{id}/report")
    fun report(principal: Principal, @PathVariable id: UUID): Report = service.report(principal.name, id)

    @PostMapping("/{id}/reanalyze")
    @ResponseStatus(HttpStatus.ACCEPTED)
    fun reanalyze(principal: Principal, @PathVariable id: UUID): InterviewDetail =
        service.reanalyze(principal.name, id)

    @PostMapping("/{id}/share")
    fun share(
        principal: Principal,
        @PathVariable id: UUID,
        @RequestBody(required = false) body: ShareRequest?,
    ): ShareLink = service.share(principal.name, id, body?.ttlDays)

    @DeleteMapping("/{id}/share")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun revoke(principal: Principal, @PathVariable id: UUID) = service.revokeShare(principal.name, id)
}

@RestController
@RequestMapping("/api/demo")
class DemoController(private val service: StubService) {

    /** Идемпотентный сид демо-данных, чтобы фронт не заполнял формы руками. */
    @PostMapping("/seed")
    fun seed(): DemoSeedResult = service.demoSeed("recruiter")
}
