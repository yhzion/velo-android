package rexssh

import (
	"cmp"
	"context"
	"slices"
	"sync"
	"time"
)

// questionTimeout is how long a login waits for one answer.
const questionTimeout = 120 * time.Second

// Question is what a login needs from the user, or from Kotlin's stores.
//
//   - hostkey: Text is the host:port the key is remembered under; KeyType and Fingerprint
//     (SHA256:…) describe the key. Answer "trust" (accept it and remember it) or "changed"
//     (the phone knows another key for this host:port: the login fails with
//     host_key_changed and asks nothing else).
//   - password: Text identifies the SSH username and host; answer with the password.
//   - prompt: a keyboard-interactive question; Text is the host's question, Echo whether
//     the answer may be shown.
//
// Retry marks a password or prompt asked again because the host refused an earlier
// answer in the same login (a password question: because it refused a password; a code
// refused before the first password question is none). A password question carries it too when a login before ended
// with the refusal of a password that no question told of: the host hung up with its
// refusal (sshd's MaxAuthTries), closed right after it, or refused the last try. Kotlin
// forgets the saved password on a password's Retry, so one the host no longer takes does
// not go out again. A Retry question is withdrawn when its connection ends (see
// login.ask); its refusal is then carried as well.
//
// AfterStall (JSON "after_stall", left out when false) marks the password and prompt
// questions of a login that follows one where the host left an answer without a reply for
// the step timeout (45 s, a slow PAM): the host may have taken that answer, or may still
// be working on it. Also of a login that follows two which the host closed after a
// password, without a reply (the second stopped as password_refused): it never said that
// the password was wrong. Kotlin asks the user instead of answering from a saved password,
// and keeps the saved one; with Retry beside it Kotlin drops only the answer it kept for
// the last login. A login that finishes, or a host that refuses an answer or the
// keys or turns the login away after the answer (login_refused's sentence, also while it
// is reported as closed_before_login), clears it; a dropped connection, a decline, the
// pool's own close or a login turned away before any answer (a key login, PAM's words
// before any question) leaves it. A
// refusal carried from a login that had the mark brings it along, also after it was cleared:
// the login that tells of that refusal is the user's as well.
type Question struct {
	ID          int64  `json:"id"`
	Kind        string `json:"kind"`
	Target      string `json:"target"`
	Text        string `json:"text"`
	KeyType     string `json:"keyType"`
	Fingerprint string `json:"fingerprint"`
	Echo        bool   `json:"echo"`
	Retry       bool   `json:"retry"`
	AfterStall  bool   `json:"after_stall,omitempty"`
}

// Asker answers questions. ok is false when the question was declined or timed out.
type Asker interface {
	Ask(ctx context.Context, q Question) (answer string, ok bool)
}

// Queue is an Asker that parks each question until Answer is called for it (Kotlin polls
// Pending), the context ends, or questionTimeout passes. A question that stops waiting
// leaves Pending.
type Queue struct {
	mu      sync.Mutex
	last    int64
	waiting map[int64]*waiting
}

type waiting struct {
	q      Question
	answer chan answer
}

type answer struct {
	text string
	ok   bool
}

func NewQueue() *Queue { return &Queue{waiting: map[int64]*waiting{}} }

// Ask gives q a fresh ID and waits for its answer.
func (q *Queue) Ask(ctx context.Context, question Question) (string, bool) {
	w := &waiting{answer: make(chan answer, 1)}
	q.mu.Lock()
	q.last++
	question.ID = q.last
	w.q = question
	q.waiting[question.ID] = w
	q.mu.Unlock()
	timer := time.NewTimer(questionTimeout)
	defer timer.Stop()
	select {
	case a := <-w.answer: // Answer took it out of waiting
		return a.text, a.ok
	case <-ctx.Done():
	case <-timer.C:
	}
	// The question stops waiting, unless Answer took it first: then its answer is in the
	// channel already, and Answer told the caller it arrived, so it counts.
	q.mu.Lock()
	_, still := q.waiting[question.ID]
	delete(q.waiting, question.ID)
	q.mu.Unlock()
	if !still {
		a := <-w.answer
		return a.text, a.ok
	}
	return "", false
}

// Pending lists the questions waiting for an answer, oldest first.
func (q *Queue) Pending() []Question {
	q.mu.Lock()
	defer q.mu.Unlock()
	out := make([]Question, 0, len(q.waiting))
	for _, w := range q.waiting {
		out = append(out, w.q)
	}
	slices.SortFunc(out, func(a, b Question) int { return cmp.Compare(a.ID, b.ID) })
	return out
}

// Answer answers question id; ok false declines it. It reports whether the question was
// still waiting, which is whether the asker gets this answer: false for a question whose
// time ran out or whose login gave up, however close the two came.
func (q *Queue) Answer(id int64, text string, ok bool) bool {
	q.mu.Lock()
	defer q.mu.Unlock()
	w := q.waiting[id]
	if w == nil {
		return false
	}
	delete(q.waiting, id)
	w.answer <- answer{text, ok} // never blocks: one slot, and only this call answers w
	return true
}
