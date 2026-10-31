/*
 * Reader extraction for Panda Mobile.
 *
 * This is a small, dependency-free re-implementation of the "main content"
 * idea used by Lightpanda's markdown dump: score paragraph containers, keep the
 * winning subtree, then whitelist readable tags and absolutise links/images.
 *
 * It runs inside the page and returns a JSON string. No network access, no
 * external libraries, no page globals are modified beyond a temporary expando.
 */
(function () {
    if (window.ReaderMode) {
        return;
    }

    var BLOCKED = "script,style,noscript,iframe,svg,canvas,form,input,button,"
        + "select,textarea,nav,aside,footer,header,[role=navigation],[role=banner],"
        + "[role=complementary],[aria-hidden=true],.ad,.ads,.advert,.advertisement,"
        + ".share,.sharing,.social,.comments,.comment,.sidebar,.related,.newsletter,"
        + ".cookie,.cookies,.popup,.modal,.subscribe,.promo,.breadcrumb";

    var ALLOWED = {
        P: 1, H1: 1, H2: 1, H3: 1, H4: 1, H5: 1, H6: 1, BLOCKQUOTE: 1, PRE: 1,
        CODE: 1, UL: 1, OL: 1, LI: 1, A: 1, IMG: 1, FIGURE: 1, FIGCAPTION: 1,
        TABLE: 1, THEAD: 1, TBODY: 1, TR: 1, TH: 1, TD: 1, STRONG: 1, EM: 1,
        B: 1, I: 1, U: 1, S: 1, BR: 1, HR: 1, SPAN: 1, DIV: 1, SUP: 1, SUB: 1,
        DL: 1, DT: 1, DD: 1, CITE: 1, Q: 1
    };

    var ALLOWED_ATTR = {
        href: 1, src: 1, alt: 1, title: 1, colspan: 1, rowspan: 1
    };

    function text(el) {
        return (el.textContent || "").replace(/\s+/g, " ").trim();
    }

    function absolute(url) {
        try {
            return new URL(url, location.href).href;
        } catch (e) {
            return url;
        }
    }

    function pickRoot() {
        var nodes = document.querySelectorAll("p, pre, blockquote");
        var best = null;
        var bestScore = 0;

        for (var i = 0; i < nodes.length; i++) {
            var length = text(nodes[i]).length;
            if (length < 40) {
                continue;
            }
            var el = nodes[i].parentElement;
            for (var depth = 0; depth < 4 && el; depth++) {
                var score = (el.__readerScore || 0) + length * (1 - depth * 0.2);
                el.__readerScore = score;
                el.__readerCount = (el.__readerCount || 0) + 1;
                if (score > bestScore) {
                    bestScore = score;
                    best = el;
                }
                el = el.parentElement;
            }
        }

        var semantic = document.querySelector("article, main, [role=main]");
        if (semantic && text(semantic).length > 300) {
            var semanticLength = text(semantic).length;
            if (!best || semanticLength > text(best).length * 0.5) {
                best = semantic;
            }
        }
        if (!best) {
            best = semantic || document.body;
        }
        return best;
    }

    function sanitize(root) {
        var clone = root.cloneNode(true);
        var blocked = clone.querySelectorAll(BLOCKED);
        for (var i = blocked.length - 1; i >= 0; i--) {
            if (blocked[i].parentNode) {
                blocked[i].parentNode.removeChild(blocked[i]);
            }
        }

        var all = clone.querySelectorAll("*");
        for (var j = all.length - 1; j >= 0; j--) {
            var el = all[j];
            if (!el.parentNode && el !== clone) {
                continue;
            }

            var imageSource = null;
            if (el.tagName === "IMG") {
                imageSource = el.getAttribute("src")
                    || el.getAttribute("data-src")
                    || el.getAttribute("data-original")
                    || el.getAttribute("data-lazy-src");
            }

            if (!ALLOWED[el.tagName]) {
                var parent = el.parentNode;
                if (!parent) {
                    continue;
                }
                while (el.firstChild) {
                    parent.insertBefore(el.firstChild, el);
                }
                parent.removeChild(el);
                continue;
            }

            for (var k = el.attributes.length - 1; k >= 0; k--) {
                var attr = el.attributes[k];
                if (!attr) {
                    continue;
                }
                var name = attr.name.toLowerCase();
                if (!ALLOWED_ATTR[name]) {
                    el.removeAttribute(attr.name);
                }
            }

            if (el.tagName === "A") {
                var href = el.getAttribute("href");
                if (!href || /^javascript:/i.test(href)) {
                    el.removeAttribute("href");
                } else {
                    el.setAttribute("href", absolute(href));
                }
            }

            if (el.tagName === "IMG") {
                if (imageSource && !/^data:image\/(gif|webp);base64,/i.test(imageSource)) {
                    el.setAttribute("src", absolute(imageSource));
                } else if (imageSource) {
                    el.setAttribute("src", imageSource);
                } else if (el.parentNode) {
                    el.parentNode.removeChild(el);
                }
            }
        }

        return clone.innerHTML;
    }

    function findTitle(root) {
        var meta = document.querySelector('meta[property="og:title"]');
        if (meta && meta.content) {
            return meta.content;
        }
        var h1 = root.querySelector("h1") || document.querySelector("h1");
        if (h1 && text(h1).length > 2) {
            return text(h1);
        }
        return document.title || location.hostname;
    }

    function findByline() {
        // A visible byline is more specific than a site-wide meta author, so
        // prefer it when the article actually renders one.
        var visible = document.querySelector(
            '[rel="author"], .byline, .author, .dateline, .article-author');
        if (visible) {
            var visibleText = text(visible);
            if (visibleText.length > 1) {
                return visibleText;
            }
        }
        var author = document.querySelector('meta[name="author"]');
        if (author && author.content) {
            return author.content;
        }
        return "";
    }

    window.ReaderMode = {
        extract: function () {
            try {
                var root = pickRoot();
                var html = sanitize(root);
                if (html.length < 200) {
                    html = sanitize(document.body);
                }
                var title = findTitle(root).replace(/\s+/g, " ").trim();
                var byline = findByline().replace(/\s+/g, " ").trim();
                if (byline.length > 160) {
                    byline = byline.slice(0, 160);
                }

                // The reader template adds its own heading and byline. Remove
                // duplicates that were part of the extracted article so the
                // result does not show the same line twice.
                var holder = document.createElement("div");
                holder.innerHTML = html;
                var heading = holder.querySelector("h1");
                if (heading && title.length > 10) {
                    var headingText = text(heading);
                    if (headingText === title
                        || title.indexOf(headingText) !== -1
                        || headingText.indexOf(title) !== -1) {
                        heading.parentNode.removeChild(heading);
                    }
                }
                if (byline) {
                    var paragraphs = holder.querySelectorAll("p");
                    var normalizedByline = byline.replace(/^by\s+/i, "");
                    for (var p = 0; p < paragraphs.length && p < 3; p++) {
                        var paragraphText = text(paragraphs[p]);
                        if (paragraphText === byline
                            || paragraphText.replace(/^by\s+/i, "") === normalizedByline) {
                            paragraphs[p].parentNode.removeChild(paragraphs[p]);
                            break;
                        }
                    }
                }
                html = holder.innerHTML;

                return JSON.stringify({
                    title: title || location.hostname,
                    byline: byline,
                    html: html,
                    url: location.href,
                    site: location.hostname,
                    chars: html.length
                });
            } catch (e) {
                return JSON.stringify({error: String(e)});
            }
        }
    };
})();
