// Package output 提供表格格式化功能。
//
// 不依赖外部库，使用 fmt 手动实现简洁的表格输出。
// 表格样式参考了飞书 CLI 的设计：无外边框、列对齐、表头加粗。
//
// 示例输出：
//
//	ID          分类       标题                            状态
//	────────    ───────    ──────────────────────────      ────────
//	4458cf32    SUMMARY    Post-turn summary candidate     PENDING
package output

import (
	"fmt"
	"io"
	"strings"
)

// Table 是一个简单的终端表格渲染器。
// 不依赖外部库，用 fmt 手动实现。
type Table struct {
	out     io.Writer  // 输出目标
	headers []string   // 表头
	rows    [][]string // 数据行
}

// NewTable 创建一个新的表格。
// headers: 表头列名列表，如 []string{"ID", "TITLE", "STATUS"}
func NewTable(out io.Writer, headers []string) *Table {
	return &Table{
		out:     out,
		headers: headers,
		rows:    make([][]string, 0),
	}
}

// Append 添加一行数据到表格。
// row: 每列的值，数量应与表头一致。
func (t *Table) Append(row []string) {
	t.rows = append(t.rows, row)
}

// Render 渲染表格到输出流。
// 自动计算每列的最大宽度，确保对齐。
func (t *Table) Render() {
	if len(t.headers) == 0 {
		return
	}

	// 1. 计算每列的最大宽度
	// 初始化为表头的宽度
	colWidths := make([]int, len(t.headers))
	for i, h := range t.headers {
		colWidths[i] = runeWidth(h)
	}

	// 遍历所有数据行，更新最大宽度
	for _, row := range t.rows {
		for i, cell := range row {
			if i < len(colWidths) {
				w := runeWidth(cell)
				if w > colWidths[i] {
					colWidths[i] = w
				}
			}
		}
	}

	// 2. 渲染表头
	fmt.Fprintln(t.out)
	t.renderRow(t.headers, colWidths)

	// 3. 渲染分隔线（使用 ─ 字符）
	separators := make([]string, len(colWidths))
	for i, w := range colWidths {
		// strings.Repeat 重复字符串 n 次
		separators[i] = strings.Repeat("─", w)
	}
	t.renderRow(separators, colWidths)

	// 4. 渲染数据行
	for _, row := range t.rows {
		t.renderRow(row, colWidths)
	}

	fmt.Fprintln(t.out)
}

// renderRow 渲染表格中的一行。
// 每列之间用三个空格分隔，每列右侧用空格填充到最大宽度。
func (t *Table) renderRow(row []string, colWidths []int) {
	fmt.Fprintf(t.out, "  ") // 左侧缩进
	for i, cell := range row {
		if i >= len(colWidths) {
			break
		}
		// 计算需要填充的空格数
		padding := colWidths[i] - runeWidth(cell)
		if padding < 0 {
			padding = 0
		}
		fmt.Fprintf(t.out, "%s%s", cell, strings.Repeat(" ", padding))
		// 列之间的分隔（最后一列不加）
		if i < len(row)-1 {
			fmt.Fprintf(t.out, "   ")
		}
	}
	fmt.Fprintln(t.out)
}

// runeWidth 计算字符串的显示宽度。
// 中文字符占 2 个字符宽度，英文字符占 1 个。
// 这是终端中正确对齐中英文混排文本的关键。
func runeWidth(s string) int {
	width := 0
	for _, r := range s {
		if r >= 0x1100 && isCJK(r) {
			width += 2 // 中日韩字符占两个字符宽度
		} else {
			width += 1
		}
	}
	return width
}

// isCJK 判断一个 Unicode 字符是否是中日韩字符。
// 这些字符在终端中占两个字符宽度。
func isCJK(r rune) bool {
	return (r >= 0x2E80 && r <= 0x9FFF) || // CJK 统一表意文字
		(r >= 0xF900 && r <= 0xFAFF) || // CJK 兼容表意文字
		(r >= 0xFE30 && r <= 0xFE4F) || // CJK 兼容形式
		(r >= 0xFF00 && r <= 0xFFEF) || // 全角字符
		(r >= 0x20000 && r <= 0x2FA1F) // CJK 扩展
}

// TruncateString 截断字符串到指定长度。
// 如果字符串超过 maxLen，截断并添加 "..." 后缀。
// 用于在表格中显示过长的文本。
func TruncateString(s string, maxLen int) string {
	// 移除换行符，表格中不应该有多行文本
	s = strings.ReplaceAll(s, "\n", " ")
	s = strings.TrimSpace(s)

	// 使用 []rune 转换可以正确处理中文等多字节字符
	runes := []rune(s)
	if len(runes) <= maxLen {
		return s
	}
	return string(runes[:maxLen-3]) + "..."
}

// TruncateID 截断 UUID 到前 8 个字符。
// UUID 格式如 "4458cf32-8072-4ba7-a1c2-f750c720afc1"，
// 前 8 个字符通常足够区分不同的 ID。
func TruncateID(id string) string {
	if len(id) > 8 {
		return id[:8] + "..."
	}
	return id
}
