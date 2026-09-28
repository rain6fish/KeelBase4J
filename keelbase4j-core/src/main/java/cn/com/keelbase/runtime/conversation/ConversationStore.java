// SPDX-License-Identifier: Apache-2.0
package cn.com.keelbase.runtime.conversation;

import cn.com.keelbase.runtime.identity.Principal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * The conversation transcript: which conversation a turn belongs to, and what was said.
 *
 * <p>Two rules, both about not letting an id mean something it does not:
 *
 * <ul>
 *   <li><b>An id the runtime has never issued is not adopted.</b> A caller that sends an unknown
 *       conversation id gets a new conversation rather than a handle onto whatever happens to be
 *       recorded under that name — the runtime issues the ids, so it does not accept foreign ones.
 *   <li><b>A known id is not a capability.</b> An id that belongs to somebody else is refused; the
 *       conversation is the owner's.
 * </ul>
 */
@Service
public class ConversationStore {

    private final ConversationMessageRepository repository;

    public ConversationStore(ConversationMessageRepository repository) {
        this.repository = repository;
    }

    /**
     * The conversation this turn belongs to: the one asked for when it is the caller's, otherwise a
     * new one. See the class comment for why an unknown id does not survive.
     */
    public String openFor(String conversationId, Principal principal) {
        if (conversationId == null || conversationId.isBlank()) {
            return newId();
        }
        Optional<ConversationMessage> first =
                repository.findFirstByConversationIdOrderByIdAsc(conversationId);
        if (first.isEmpty()) {
            return newId();
        }
        if (!first.get().getUserId().equals(principal.userId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "conversation belongs to another user");
        }
        return conversationId;
    }

    public ConversationMessage append(String conversationId, Principal principal, String role, String content) {
        return repository.save(new ConversationMessage(conversationId, principal.userId(), role, content));
    }

    /**
     * The recent turns, oldest first — the order a reader (or a model) expects.
     *
     * <p>Bounded on purpose: a conversation is unbounded and a replier needs the recent context, not
     * the whole history.
     */
    public List<ConversationMessage> history(String conversationId) {
        List<ConversationMessage> newestFirst =
                repository.findTop20ByConversationIdOrderByIdDesc(conversationId);
        List<ConversationMessage> oldestFirst = new ArrayList<>(newestFirst);
        Collections.reverse(oldestFirst);
        return oldestFirst;
    }

    private static String newId() {
        return UUID.randomUUID().toString();
    }
}
