import { useState } from "react";
import {
  Box,
  Heading,
  Text,
  Button,
  IconButton,
  TextInput,
  Textarea,
  Select,
  Label,
  Token,
  Flash,
  Spinner,
  ActionMenu,
  ActionList,
  UnderlineNav,
  Link,
  ProgressBar,
  Octicon,
} from "@primer/react";
import {
  RepoIcon,
  GitBranchIcon,
  StarIcon,
  SearchIcon,
  TrashIcon,
  PencilIcon,
  GitCommitIcon,
  IssueOpenedIcon,
} from "@primer/octicons-react";

import PageContainer from "../components/layout/PageContainer";
import { EmptyState, ErrorState, LoadingState } from "../components/common/states";
import Notice from "../components/common/Notice";
import IdentityAvatar from "../components/common/IdentityAvatar";
import { tokens, laneColors } from "../theme/gitforge";

/**
 * An internal reference for the GitForge design language.
 *
 * Exists so the theme can be judged as a whole — every component side by side,
 * in one place — rather than discovered piecemeal while building real pages. It
 * is far cheaper to notice that a border is too bright here than across eight
 * screens that already use it.
 *
 * Not linked from anywhere in the product; reachable only at /_design.
 */
const DesignSystem = () => {
  const [tab, setTab] = useState("Code");

  return (
    <PageContainer>
      <Box sx={{ mb: 4 }}>
        <Heading as="h1" sx={{ fontSize: 4, mb: 1 }}>
          GitForge design system
        </Heading>
        <Text sx={{ color: "fg.muted", fontSize: 1 }}>
          Internal reference. Slate surfaces, ember accent.
        </Text>
      </Box>

      <Section title="Palette">
        <Box sx={{ display: "flex", flexWrap: "wrap", gap: 2 }}>
          {Object.entries(tokens)
            .filter(([, value]) => value.startsWith("#"))
            .map(([name, value]) => (
              <Box
                key={name}
                sx={{
                  border: "1px solid",
                  borderColor: "border.default",
                  borderRadius: 2,
                  overflow: "hidden",
                  width: "132px",
                }}
              >
                <Box sx={{ height: "48px", bg: value }} />
                <Box sx={{ p: 2, bg: "canvas.subtle" }}>
                  <Text sx={{ fontSize: 0, fontWeight: 600, display: "block" }}>{name}</Text>
                  <Text sx={{ fontSize: 0, color: "fg.muted", fontFamily: "mono" }}>{value}</Text>
                </Box>
              </Box>
            ))}
        </Box>
      </Section>

      <Section title="Graph lanes">
        <Box sx={{ display: "flex", gap: 2, alignItems: "center" }}>
          {laneColors.map((color, index) => (
            <Box key={color} sx={{ display: "flex", alignItems: "center", gap: 1 }}>
              <Box sx={{ width: "14px", height: "14px", borderRadius: "50%", bg: color }} />
              <Text sx={{ fontSize: 0, color: "fg.muted", fontFamily: "mono" }}>{index}</Text>
            </Box>
          ))}
        </Box>
      </Section>

      <Section title="Typography">
        <Heading as="h1" sx={{ fontSize: 5, mb: 2 }}>Heading 1 — repository name</Heading>
        <Heading as="h2" sx={{ fontSize: 3, mb: 2 }}>Heading 2 — section</Heading>
        <Heading as="h3" sx={{ fontSize: 2, mb: 2 }}>Heading 3 — card title</Heading>
        <Text as="p" sx={{ mb: 2, maxWidth: "60ch" }}>
          Body text at the default size. Long-form content is capped in width so lines
          stay readable rather than stretching the full width of a wide display.
        </Text>
        <Text as="p" sx={{ color: "fg.muted", fontSize: 1, mb: 2 }}>
          Muted secondary text, used for timestamps and supporting detail.
        </Text>
        <Text as="p" sx={{ color: "fg.subtle", fontSize: 0 }}>
          Subtle tertiary text, the quietest level.
        </Text>
      </Section>

      <Section title="Monospace and code">
        <Box sx={{ display: "flex", flexDirection: "column", gap: 2 }}>
          <Text sx={{ fontFamily: "mono", fontSize: 1 }}>
            9927fc5 <Text sx={{ color: "fg.muted" }}>· src/main/java/App.java</Text>
          </Text>
          <Box
            sx={{
              fontFamily: "mono",
              fontSize: 0,
              bg: "canvas.inset",
              border: "1px solid",
              borderColor: "border.default",
              borderRadius: 2,
              p: 3,
              overflowX: "auto",
            }}
          >
            <Box sx={{ color: "fg.muted" }}>@@ -1,4 +1,5 @@</Box>
            <Box sx={{ whiteSpace: "pre" }}> def main():</Box>
            <Box sx={{ whiteSpace: "pre", bg: "danger.subtle", color: "danger.fg" }}>
              -    print(&quot;hello&quot;)
            </Box>
            <Box sx={{ whiteSpace: "pre", bg: "success.subtle", color: "success.fg" }}>
              +    print(f&quot;hello world&quot;)
            </Box>
            <Box sx={{ whiteSpace: "pre" }}> return 0</Box>
          </Box>
        </Box>
      </Section>

      <Section title="Buttons">
        <Box sx={{ display: "flex", flexWrap: "wrap", gap: 2, alignItems: "center", mb: 3 }}>
          <Button variant="primary">Primary</Button>
          <Button>Default</Button>
          <Button variant="danger">Danger</Button>
          <Button variant="invisible">Invisible</Button>
          <Button disabled>Disabled</Button>
        </Box>
        <Box sx={{ display: "flex", flexWrap: "wrap", gap: 2, alignItems: "center" }}>
          <Button size="small" leadingVisual={GitBranchIcon}>Small with icon</Button>
          <Button size="medium" leadingVisual={StarIcon}>Medium</Button>
          <Button size="large" variant="primary">Large</Button>
          <IconButton icon={PencilIcon} aria-label="Edit" />
          <IconButton icon={TrashIcon} aria-label="Delete" variant="danger" />
        </Box>
      </Section>

      <Section title="Inputs">
        <Box sx={{ display: "grid", gap: 3, maxWidth: "460px" }}>
          <TextInput aria-label="Search" leadingVisual={SearchIcon} placeholder="Search repositories" />
          <TextInput aria-label="Name" placeholder="Repository name" />
          <TextInput aria-label="Invalid" placeholder="Invalid input" validationStatus="error" />
          <Select aria-label="Visibility">
            <Select.Option value="public">Public</Select.Option>
            <Select.Option value="private">Private</Select.Option>
          </Select>
          <Textarea aria-label="Description" placeholder="Describe this repository" rows={3} />
        </Box>
      </Section>

      <Section title="Badges and labels">
        <Box sx={{ display: "flex", flexWrap: "wrap", gap: 2, alignItems: "center" }}>
          <Label>Public</Label>
          <Label variant="accent">main</Label>
          <Label variant="success">Open</Label>
          <Label variant="danger">Conflict</Label>
          <Label variant="attention">Draft</Label>
          <Label variant="secondary">Private</Label>
          <Token text="java" />
          <Token text="merge commit" />
        </Box>
      </Section>

      <Section title="Tabs">
        <UnderlineNav aria-label="Repository">
          {["Code", "Commits", "Branches", "Issues", "Insights"].map((item) => (
            <UnderlineNav.Item
              key={item}
              aria-current={item === tab ? "page" : undefined}
              onSelect={(event) => {
                event.preventDefault();
                setTab(item);
              }}
              icon={item === "Code" ? RepoIcon : item === "Commits" ? GitCommitIcon : undefined}
              counter={item === "Issues" ? 3 : undefined}
            >
              {item}
            </UnderlineNav.Item>
          ))}
        </UnderlineNav>
      </Section>

      <Section title="Cards">
        <Box sx={{ display: "grid", gap: 3, gridTemplateColumns: ["1fr", "1fr", "1fr 1fr"] }}>
          <Card>
            <Box sx={{ display: "flex", alignItems: "center", gap: 2, mb: 1 }}>
              <Octicon icon={RepoIcon} sx={{ color: "fg.muted" }} />
              <Link href="#" sx={{ fontWeight: 600, fontSize: 2 }}>gitforge</Link>
              <Label variant="secondary">Public</Label>
            </Box>
            <Text as="p" sx={{ color: "fg.muted", fontSize: 1, mb: 2 }}>
              A version control system built from first principles.
            </Text>
            <Box sx={{ display: "flex", gap: 3, color: "fg.subtle", fontSize: 0 }}>
              <Text>Java</Text>
              <Text>Updated 2 hours ago</Text>
            </Box>
          </Card>
          <Card>
            <Box sx={{ display: "flex", alignItems: "center", gap: 2, mb: 1 }}>
              <IdentityAvatar username="ada" size={20} />
              <Text sx={{ fontWeight: 600, fontSize: 1 }}>Latest commit</Text>
            </Box>
            <Text as="p" sx={{ fontSize: 1, mb: 1 }}>Add line-level diff engine</Text>
            <Text sx={{ fontFamily: "mono", fontSize: 0, color: "fg.muted" }}>9927fc5 · 3 files changed</Text>
          </Card>
        </Box>
      </Section>

      <Section title="Dropdown">
        <ActionMenu>
          <ActionMenu.Button leadingVisual={GitBranchIcon}>main</ActionMenu.Button>
          <ActionMenu.Overlay>
            <ActionList>
              <ActionList.Item selected>main</ActionList.Item>
              <ActionList.Item>feature/login</ActionList.Item>
              <ActionList.Item>release-1.0</ActionList.Item>
              <ActionList.Divider />
              <ActionList.Item variant="danger">Delete branch</ActionList.Item>
            </ActionList>
          </ActionMenu.Overlay>
        </ActionMenu>
      </Section>

      <Section title="Alerts">
        <Box sx={{ display: "grid", gap: 2 }}>
          <Notice>Informational notice: neutral, so colour is reserved for meaning.</Notice>
          <Notice variant="success">Merged 3 commits into main.</Notice>
          <Notice variant="warning">This branch is 2 commits behind main.</Notice>
          <Notice variant="danger">Merge conflict in src/App.java.</Notice>
        </Box>
        <Text sx={{ display: "block", mt: 3, mb: 2, fontSize: 0, color: "fg.subtle" }}>
          Primer Flash, for comparison — its default variant is accent-tinted:
        </Text>
        <Box sx={{ display: "grid", gap: 2 }}>
          <Flash variant="success">Flash success</Flash>
          <Flash variant="danger">Flash danger</Flash>
        </Box>
      </Section>

      <Section title="Progress and activity">
        <Box sx={{ display: "grid", gap: 3, maxWidth: "460px" }}>
          <ProgressBar progress={68} />
          <Box sx={{ display: "flex", alignItems: "center", gap: 2 }}>
            <Spinner size="small" />
            <Text sx={{ fontSize: 1, color: "fg.muted" }}>Inline spinner</Text>
          </Box>
        </Box>
      </Section>

      <Section title="States">
        <Box sx={{ display: "grid", gap: 3, gridTemplateColumns: ["1fr", "1fr", "repeat(3, 1fr)"] }}>
          <Card><LoadingState label="Loading repositories" /></Card>
          <Card>
            <ErrorState message="Cannot reach the server. Is it running?" onRetry={() => {}} />
          </Card>
          <Card>
            <EmptyState
              icon={IssueOpenedIcon}
              title="No issues yet"
              message="Open one to start tracking work."
              action={<Button size="small" variant="primary">New issue</Button>}
            />
          </Card>
        </Box>
      </Section>
    </PageContainer>
  );
};

const Section = ({ title, children }) => (
  <Box as="section" sx={{ mb: 5 }}>
    <Heading
      as="h2"
      sx={{
        fontSize: 1,
        textTransform: "uppercase",
        letterSpacing: "0.06em",
        color: "fg.subtle",
        mb: 3,
        pb: 2,
        borderBottom: "1px solid",
        borderColor: "border.muted",
      }}
    >
      {title}
    </Heading>
    {children}
  </Box>
);

const Card = ({ children }) => (
  <Box
    sx={{
      bg: "canvas.subtle",
      border: "1px solid",
      borderColor: "border.default",
      borderRadius: 2,
      p: 3,
    }}
  >
    {children}
  </Box>
);

export default DesignSystem;
