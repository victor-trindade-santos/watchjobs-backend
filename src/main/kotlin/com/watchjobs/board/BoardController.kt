package com.watchjobs.board

import com.watchjobs.security.AppUserDetails
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestMethod
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/saved-jobs")
class BoardController(private val service: BoardService) {

    @GetMapping("/board")
    fun board(@AuthenticationPrincipal principal: AppUserDetails): BoardResponse = service.board(principal.id)

    // POST também vale: o HttpURLConnection do Android não envia PATCH.
    @RequestMapping("/{id}/move", method = [RequestMethod.PATCH, RequestMethod.POST])
    fun move(
        @AuthenticationPrincipal principal: AppUserDetails,
        @PathVariable id: Long,
        @RequestBody request: MoveRequest
    ): BoardCard = service.move(principal.id, id, request)

    @GetMapping("/{id}/timeline")
    fun timeline(@AuthenticationPrincipal principal: AppUserDetails, @PathVariable id: Long): List<TimelineEvent> =
        service.timeline(principal.id, id)
}
