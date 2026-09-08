import { useEffect } from 'react';

/**
 * PageMeta Component
 * Updates the document title and favicon dynamically based on props.
 *
 * @param {string} title - The text to display in the browser tab.
 * @param {string} emoji - The emoji to use as the favicon.
 * @param {React.ReactNode} children - The child components to render (usually the route component).
 */
const PageMeta = ({ title, emoji, children }) => {
  useEffect(() => {
    // Update Document Title
    document.title = title || 'Hospital Management System';

    // Update Favicon using Emoji
    if (emoji) {
      let link = document.querySelector("link[rel~='icon']");
      if (!link) {
        link = document.createElement('link');
        link.rel = 'icon';
        document.head.appendChild(link);
      }
      link.type = 'image/svg+xml';

      // Create an SVG string containing the emoji
      const svg = `
                <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 100 100">
                    <text y=".9em" font-size="90">${emoji}</text>
                </svg>
            `.trim();

      link.href = `data:image/svg+xml,${encodeURIComponent(svg)}`;
    }
  }, [title, emoji]);

  return children;
};

export default PageMeta;
