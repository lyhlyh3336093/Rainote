import MarkdownIt from 'markdown-it';
import hljs from 'highlight.js';
import DOMPurify from 'dompurify';

/**
 * Markdown-it 配置
 * - html: false — 不允许原始HTML透传（XSS防护第一层）
 * - highlight: 使用highlight.js进行语法高亮
 *
 * XSS防护第二层（R6a/Group B）：DOMPurify sanitize
 * 即使 html:false 已阻断大部分原始HTML，仍可能存在 markdown-it 转换出的
 * 危险链接（如 javascript: scheme）等边缘场景。renderMarkdown 在渲染后统一净化。
 */
const md = new MarkdownIt({
    html: false,
    linkify: true,
    typographer: true,
    highlight: function (str: string, lang: string): string {
        if (lang && hljs.getLanguage(lang)) {
            try {
                return '<pre><code class="hljs">' +
                    hljs.highlight(str, { language: lang, ignoreIllegals: true }).value +
                    '</code></pre>';
            } catch {
                // fall through to default
            }
        }
        return '<pre><code class="hljs">' + md.utils.escapeHtml(str) + '</code></pre>';
    }
});

/** DOMPurify 允许的标签白名单（覆盖 markdown-it 输出） */
const ALLOWED_TAGS = [
    'p', 'br', 'strong', 'em', 'code', 'pre', 'span',
    'ul', 'ol', 'li', 'blockquote',
    'h1', 'h2', 'h3', 'h4', 'h5', 'h6',
    'table', 'thead', 'tbody', 'tr', 'th', 'td',
    'a', 'img', 'hr', 'del', 'ins', 'sub', 'sup',
];

/** DOMPurify 允许的属性白名单 */
const ALLOWED_ATTR = ['href', 'src', 'alt', 'title', 'class', 'target', 'rel'];

/**
 * 渲染 Markdown 并净化 HTML（R6a/Group B）
 * 管道：md.render(text) → DOMPurify.sanitize → setInnerHTML
 */
export function renderMarkdown(src: string): string {
    const html = md.render(src);
    return DOMPurify.sanitize(html, { ALLOWED_TAGS, ALLOWED_ATTR });
}

export default md;
