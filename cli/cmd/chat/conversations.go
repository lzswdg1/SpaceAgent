package chat

import (
	"fmt"
	"net/url"

	"github.com/spf13/cobra"

	"github.com/lzswdg1/SpaceAgent/cli/internal/client"
	"github.com/lzswdg1/SpaceAgent/cli/internal/factory"
	"github.com/lzswdg1/SpaceAgent/cli/internal/output"
)

func NewConversationsCmd(f *factory.Factory) *cobra.Command {
	var page, size int
	cmd := &cobra.Command{
		Use:   "conversations",
		Short: "List conversations",
		RunE: func(cmd *cobra.Command, args []string) error {
			return runConversations(f, page, size)
		},
	}
	cmd.Flags().IntVar(&page, "page", 1, "Page number")
	cmd.Flags().IntVar(&size, "size", 20, "Page size")
	return cmd
}

func runConversations(f *factory.Factory, page, size int) error {
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

	s := output.StartSpinner(" Loading conversations...")
	var resp client.ApiResponse[client.PageResponse[client.ConversationSummaryItemResponse]]
	err = c.GetWithParams("/api/v1/chat/conversations", params, &resp)
	output.StopSpinner(s)
	if err != nil {
		output.Error(out, "Failed to load conversations: %s", err)
		return err
	}

	if len(resp.Data.Items) == 0 {
		output.Info(out, "No conversations")
		return nil
	}

	table := output.NewTable(out, []string{"ID", "Title", "Status", "Started", "Last Message"})
	for _, item := range resp.Data.Items {
		table.Append([]string{
			output.TruncateID(item.ConversationId),
			output.TruncateString(item.Title, 28),
			item.Status,
			output.FormatTime(item.StartedAt),
			output.FormatTime(item.LastMessageAt),
		})
	}
	table.Render()
	return nil
}
