import { useEffect } from 'react';

export function useVisualViewport() {
  useEffect(() => {
    const viewport = window.visualViewport;
    const root = document.documentElement.style;
    const update = () => {
      root.setProperty('--visual-viewport-height', `${viewport?.height ?? window.innerHeight}px`);
      root.setProperty('--visual-viewport-top', `${viewport?.offsetTop ?? 0}px`);
      const focused = document.activeElement;
      if (focused instanceof HTMLElement && focused.closest('[data-slot="dialog-content"]')) {
        requestAnimationFrame(() => focused.scrollIntoView({ block: 'nearest', inline: 'nearest' }));
      }
    };
    update();
    viewport?.addEventListener('resize', update);
    viewport?.addEventListener('scroll', update);
    window.addEventListener('resize', update);
    return () => {
      viewport?.removeEventListener('resize', update);
      viewport?.removeEventListener('scroll', update);
      window.removeEventListener('resize', update);
    };
  }, []);
}
