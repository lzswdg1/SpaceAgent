package chat

import (
	"fmt"

	"github.com/spf13/cobra"

	"github.com/lzswdg1/SpaceAgent/cli/internal/factory"
	"github.com/lzswdg1/SpaceAgent/cli/internal/output"
)

func NewCloseCmd(f *factory.Factory) *cobra.Command {
	return &cobra.Command{
		Use:   "close [conversation-id]",
		Short: "Close a conversation",
		Args:  cobra.ExactArgs(1),
		RunE: func(cmd *cobra.Command, args []string) error {
			return runCloseConversation(f, args[0])
		},
	}
}

func runCloseConversation(f *factory.Factory, conversationID string) error {
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

	s := output.StartSpinner(" Closing conversation...")
	err = c.Delete("/api/v1/chat/conversations/"+conversationID, nil)
	output.StopSpinner(s)
	if err != nil {
		output.Error(out, "Failed to close conversation: %s", err)
		return err
	}

	output.Success(out, "Conversation deleted: %s", output.TruncateID(conversationID))
	return nil
}
