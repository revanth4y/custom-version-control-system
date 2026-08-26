import { ActionList, ActionMenu, Box } from "@primer/react";
import Octicon from "./Octicon";
import { DeviceDesktopIcon, MoonIcon, SunIcon } from "@primer/octicons-react";

import { useColorModeContext } from "../../context/useColorModeContext";

const OPTIONS = [
  { mode: "light", label: "Light", icon: SunIcon },
  { mode: "dark", label: "Dark", icon: MoonIcon },
  { mode: "system", label: "System", icon: DeviceDesktopIcon },
];

/**
 * Choosing light, dark, or whatever the operating system says.
 *
 * A menu rather than a two-state switch, because there are genuinely three
 * choices and "follow my system" is not the same as either fixed value. A
 * toggle would have to either hide that option or lie about it.
 *
 * The icon shows what is currently in effect, so someone on `system` at night
 * sees a moon — which is the truth about what they are looking at.
 */
const ThemeToggle = () => {
  const { mode, scheme, setMode } = useColorModeContext();
  const effective = mode === "system" ? scheme : mode;
  const CurrentIcon = effective === "dark" ? MoonIcon : SunIcon;

  return (
    <ActionMenu>
      <ActionMenu.Anchor>
        <Box
          as="button"
          type="button"
          aria-label={`Theme: ${mode}. Change theme`}
          sx={{
            display: "flex",
            alignItems: "center",
            justifyContent: "center",
            width: "32px",
            height: "32px",
            bg: "transparent",
            border: "1px solid",
            borderColor: "border.default",
            borderRadius: 2,
            cursor: "pointer",
            color: "fg.muted",
            "&:hover": { borderColor: "accent.emphasis", color: "fg.default" },
          }}
        >
          <Octicon icon={CurrentIcon} size={16} />
        </Box>
      </ActionMenu.Anchor>

      <ActionMenu.Overlay align="end">
        <ActionList selectionVariant="single">
          {OPTIONS.map((option) => (
            <ActionList.Item
              key={option.mode}
              selected={mode === option.mode}
              onSelect={() => setMode(option.mode)}
            >
              <ActionList.LeadingVisual>
                <Octicon icon={option.icon} />
              </ActionList.LeadingVisual>
              {option.label}
            </ActionList.Item>
          ))}
        </ActionList>
      </ActionMenu.Overlay>
    </ActionMenu>
  );
};

export default ThemeToggle;
