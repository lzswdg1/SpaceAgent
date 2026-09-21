package output

import (
	"fmt"
	"io"

	"github.com/fatih/color"
)

// ThinkingStream renders model-provided reasoning chunks when the user opts in.
type ThinkingStream struct {
	out     io.Writer
	started bool
	open    bool
}

func NewThinkingStream(out io.Writer) *ThinkingStream {
	return &ThinkingStream{out: out}
}

func (s *ThinkingStream) Write(content string) {
	if content == "" {
		return
	}
	if !s.open {
		fmt.Fprintf(s.out, "  %s ", color.HiBlackString("思考 >"))
		s.open = true
	}
	s.started = true
	fmt.Fprint(s.out, color.HiBlackString(content))
}

func (s *ThinkingStream) Close() {
	if s.open {
		fmt.Fprintln(s.out)
		s.open = false
	}
}

func (s *ThinkingStream) Started() bool {
	return s.started
}
