import {
  IconBriefcase,
  IconSchool,
  IconUsers,
  IconShoppingCart,
  IconRun,
  IconCash,
  IconDeviceGamepad2,
  IconBook,
  IconPalette,
  IconPlant2,
  IconHome,
  IconPlane,
  IconMusic,
  IconPill,
  IconPaw,
  IconFolder,
} from '@tabler/icons-react';

export const GROUP_ICONS: Record<string, React.ComponentType<{ className?: string }>> = {
  briefcase: IconBriefcase,
  school: IconSchool,
  users: IconUsers,
  shopping: IconShoppingCart,
  run: IconRun,
  cash: IconCash,
  game: IconDeviceGamepad2,
  book: IconBook,
  palette: IconPalette,
  plant: IconPlant2,
  home: IconHome,
  plane: IconPlane,
  music: IconMusic,
  pill: IconPill,
  paw: IconPaw,
  folder: IconFolder,
};

export const GROUP_ICON_KEYS = Object.keys(GROUP_ICONS);

export function getGroupIcon(key: string) {
  return GROUP_ICONS[key] ?? IconFolder;
}
