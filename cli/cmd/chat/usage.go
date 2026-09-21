package chat

import (
	"fmt"
	"io"
	"net/url"

	"github.com/fatih/color"
	"github.com/spf13/cobra"

	"github.com/lzswdg1/SpaceAgent/cli/internal/client"
	"github.com/lzswdg1/SpaceAgent/cli/internal/factory"
	"github.com/lzswdg1/SpaceAgent/cli/internal/output"
)

type usageSummary struct {
	InputTokens  int
	OutputTokens int
	TotalTokens  int
	UsageSource  string
}

func NewUsageCmd(f *factory.Factory) *cobra.Command {
	var page, size int

	cmd := &cobra.Command{
		Use:   "usage [conversation-id]",
		Short: "Show token usage for a conversation",
		Args:  cobra.ExactArgs(1),
		RunE: func(cmd *cobra.Command, args []string) error {
			return runConversationUsage(f, args[0], page, size)
		},
	}

	cmd.Flags().IntVar(&page, "page", 1, "Page number")
	cmd.Flags().IntVar(&size, "size", 20, "Page size")

	return cmd
}

func runConversationUsage(f *factory.Factory, conversationID string, page, size int) error {
	out := f.IOStreams.Out

	cfg, err := f.Config()
	if err != nil || !cfg.IsLoggedIn() {
		output.ErrorWithHint(out, "Please login first", "Use spaceagent auth login")
		return fmt.Errorf("not logged in")
	}

	c, err := f.Client()
	if err != nil {
		return err
	}

	params := url.Values{}
	params.Set("page", fmt.Sprintf("%d", page))
	params.Set("size", fmt.Sprintf("%d", size))

	s := output.StartSpinner(" Loading usage...")
	var resp client.ApiResponse[client.PageResponse[client.ChatTurnUsageResponse]]
	err = c.GetWithParams("/api/v1/chat/conversations/"+conversationID+"/usage", params, &resp)
	output.StopSpinner(s)
	if err != nil {
		output.Error(out, "Failed to load usage: %s", err)
		return err
	}

	if len(resp.Data.Items) == 0 {
		output.Info(out, "No usage records")
		return nil
	}

	table := output.NewTable(out, []string{"Time", "Model", "Input", "Output", "Total", "Source"})
	totalInput := 0
	totalOutput := 0
	totalTokens := 0
	for _, item := range resp.Data.Items {
		table.Append([]string{
			output.FormatTime(item.CreatedAt),
			output.TruncateString(item.ModelID, 28),
			fmt.Sprintf("%d", item.InputTokens),
			fmt.Sprintf("%d", item.OutputTokens),
			fmt.Sprintf("%d", item.TotalTokens),
			item.UsageSource,
		})
		totalInput += item.InputTokens
		totalOutput += item.OutputTokens
		totalTokens += item.TotalTokens
	}
	table.Render()

	output.Hint(out, "Conversation: %s", conversationID)
	output.Hint(out, "Page %d, total records %d", resp.Data.Page, resp.Data.Total)
	output.Hint(out, "Input %d, output %d, total %d", totalInput, totalOutput, totalTokens)

	return nil
}

func renderUsageSummary(out io.Writer, usage usageSummary) {
	if usage.TotalTokens <= 0 && usage.InputTokens <= 0 && usage.OutputTokens <= 0 && usage.UsageSource == "" {
		return
	}

	source := usage.UsageSource
	if source == "" {
		source = "UNKNOWN"
	}

	fmt.Fprintf(out, "  %s %s\n",
		color.GreenString("•"),
		color.HiBlackString("tokens in:%d out:%d total:%d source:%s",
			usage.InputTokens, usage.OutputTokens, usage.TotalTokens, source))
}

func extractUsageSummary(data map[string]interface{}) usageSummary {
	return usageSummary{
		InputTokens:  numberValue(data["inputTokenCount"]),
		OutputTokens: numberValue(data["outputTokenCount"]),
		TotalTokens:  numberValue(data["totalTokenCount"]),
		UsageSource:  stringValue(data["usageSource"]),
	}
}

func numberValue(value interface{}) int {
	switch v := value.(type) {
	case float64:
		return int(v)
	case float32:
		return int(v)
	case int:
		return v
	case int32:
		return int(v)
	case int64:
		return int(v)
	default:
		return 0
	}
}

func stringValue(value interface{}) string {
	if s, ok := value.(string); ok {
		return s
	}
	return ""
}
