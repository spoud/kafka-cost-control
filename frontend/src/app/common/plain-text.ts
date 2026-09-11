/**
 * Strip the inline markdown an LLM emits even when asked for plain prose.
 *
 * The assistant renders replies with `{{ }}` interpolation, so any markup arrives on screen
 * literally: a model writing `**2.04 TB**` shows the asterisks. Rendering markdown properly would
 * mean parsing model output into HTML, which is an XSS surface this app does not otherwise have,
 * so the markers are removed instead.
 *
 * Deliberately conservative: only emphasis and code spans, and only where the delimiters actually
 * pair up on one line. Block structure (tables, lists) is left intact — `white-space: pre-wrap`
 * keeps it readable, and rewriting it risks mangling real content such as a topic name.
 */
export function stripInlineMarkdown(text: string): string {
    if (!text) {
        return text;
    }
    return text
        .split('\n')
        .map(line =>
            line
                // **bold** and __bold__
                .replace(/\*\*(?=\S)([^*]+?)(?<=\S)\*\*/g, '$1')
                .replace(/__(?=\S)([^_]+?)(?<=\S)__/g, '$1')
                // `code`
                .replace(/`(?=\S)([^`]+?)(?<=\S)`/g, '$1')
        )
        .join('\n');
}
