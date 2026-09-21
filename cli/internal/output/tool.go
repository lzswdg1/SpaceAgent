package output

import (
	"fmt"
	"io"

	"github.com/fatih/color"
)

func ToolCall(out io.Writer, content string) {
	if content == "" {
		content = "tool"
	}
	fmt.Fprintf(out, "  %s %s\n", color.BlueString("工具 >"), color.BlueString(content))
}

func ToolResult(out io.Writer, content string) {
	if content == "" {
		content = "(empty result)"
	}
	fmt.Fprintf(out, "  %s %s\n", color.GreenString("结果 >"), color.HiBlackString(content))
}
