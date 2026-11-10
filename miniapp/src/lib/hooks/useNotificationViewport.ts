import { useLayoutEffect, useRef } from 'react';

export function useNotificationViewport(route: string) {
  const workspace = useRef<HTMLDivElement>(null);
  const header = useRef<HTMLElement>(null);
  const navigation = useRef<HTMLElement>(null);
  const captureDock = useRef<HTMLDivElement>(null);
  useLayoutEffect(() => {
    const root = document.documentElement.style;
    const update = () => {
      const bounds = workspace.current?.getBoundingClientRect();
      if (!bounds) return;
      const viewport = window.visualViewport;
      const bottom = (viewport?.offsetTop ?? 0) + (viewport?.height ?? window.innerHeight);
      const visibleDock = captureDock.current?.getBoundingClientRect();
      const nav = navigation.current?.getBoundingClientRect();
      const footerTop = visibleDock?.height ? visibleDock.top : nav?.height ? nav.top : bottom;
      const top = Math.max(header.current?.getBoundingClientRect().bottom ?? 0, viewport?.offsetTop ?? 0) + 12;
      const lowerEdge = Math.min(footerTop, bottom) - 12;
      const mainWidth = workspace.current?.querySelector('main')?.clientWidth ?? bounds.width;
      root.setProperty('--notice-center', `${bounds.x + mainWidth / 2}px`);
      root.setProperty('--notice-top', `${top}px`);
      root.setProperty('--notice-bottom', `${Math.max(12, window.innerHeight - lowerEdge)}px`);
      root.setProperty('--notice-height', `${Math.max(0, lowerEdge - top)}px`);
      root.setProperty('--notice-width', `${Math.min(440, bounds.width - 32)}px`);
    };
    const observer = new ResizeObserver(update);
    [workspace, header, navigation, captureDock].forEach(ref => { if (ref.current) observer.observe(ref.current); });
    update();
    window.addEventListener('resize', update);
    window.visualViewport?.addEventListener('resize', update);
    window.visualViewport?.addEventListener('scroll', update);
    return () => {
      observer.disconnect();
      window.removeEventListener('resize', update);
      window.visualViewport?.removeEventListener('resize', update);
      window.visualViewport?.removeEventListener('scroll', update);
      ['center', 'top', 'bottom', 'height', 'width'].forEach(key => root.removeProperty(`--notice-${key}`));
    };
  }, [route]);
  return { workspace, header, navigation, captureDock };
}
