import RouterLink from "../common/RouterLink";
import { Box, Label, Link, Text } from "@primer/react";
import Octicon from "../common/Octicon";
import { AlertIcon, FileDirectoryFillIcon, FileIcon, TrashIcon } from "@primer/octicons-react";

import { countByKind, deletedBy, describeKind, describeRange } from "../../utils/merge";
import { splitPath } from "../../utils/diff";

/**
 * Every path the merge could not reconcile.
 *
 * Each conflict is shown with all three of its sides, because that is what
 * identifies the versions involved. A side that is absent is stated as absent
 * rather than omitted: which one is missing is the data, and for a
 * modify/delete it is the whole meaning of the conflict.
 */
const ConflictList = ({ conflicts, owner, name, target, source }) => (
  <Box>
    <Box sx={{ display: "flex", alignItems: "center", gap: 2, flexWrap: "wrap", mb: 3 }}>
      <Octicon icon={AlertIcon} sx={{ color: "attention.fg" }} />
      <Text sx={{ fontSize: 1, fontWeight: 600 }}>
        {conflicts.length} {conflicts.length === 1 ? "conflict" : "conflicts"}
      </Text>
      {countByKind(conflicts).map(({ kind, count }) => (
        <Label key={kind} sx={{ color: "attention.fg", borderColor: "attention.muted" }}>
          {describeKind(kind).label} × {count}
        </Label>
      ))}
    </Box>

    <Box sx={{ display: "flex", flexDirection: "column", gap: 3 }}>
      {conflicts.map((conflict) => (
        <Conflict
          key={`${conflict.kind}-${conflict.path}`}
          conflict={conflict}
          owner={owner}
          name={name}
          target={target}
          source={source}
        />
      ))}
    </Box>
  </Box>
);

const Conflict = ({ conflict, owner, name, target, source }) => {
  const kind = describeKind(conflict.kind);
  const removedBy = deletedBy(conflict);
  const { directory, name: fileName } = splitPath(conflict.path);

  return (
    <Box
      sx={{
        border: "1px solid",
        borderColor: "attention.muted",
        borderRadius: 2,
        bg: "canvas.subtle",
        overflow: "hidden",
      }}
    >
      <Box
        sx={{
          display: "flex",
          alignItems: "center",
          gap: 2,
          flexWrap: "wrap",
          px: 3,
          py: 2,
          bg: "attention.subtle",
          borderBottom: "1px solid",
          borderColor: "attention.muted",
        }}
      >
        <Text sx={{ fontFamily: "mono", fontSize: 1, minWidth: 0, overflowWrap: "anywhere" }}>
          <Text as="span" sx={{ color: "fg.muted" }}>
            {directory}
          </Text>
          <Text as="span" sx={{ fontWeight: 600 }}>
            {fileName}
          </Text>
        </Text>
        <Label sx={{ color: "attention.fg", borderColor: "attention.muted", flexShrink: 0 }}>
          {kind.label}
        </Label>
      </Box>

      <Box sx={{ px: 3, py: 3 }}>
        <Text sx={{ fontSize: 0, color: "fg.muted", display: "block", mb: 3 }}>
          {kind.summary}
          {removedBy && (
            <Text as="span">
              {" "}
              Here the {removedBy === "theirs" ? "source" : "target"} branch removed it.
            </Text>
          )}
        </Text>

        <Box
          sx={{
            display: "grid",
            gridTemplateColumns: ["1fr", "1fr", "repeat(3, minmax(0, 1fr))"],
            gap: 2,
          }}
        >
          <Side title="base" subtitle="common ancestor" side={conflict.base} />
          <Side title="ours" subtitle={target} side={conflict.ours} accent />
          <Side title="theirs" subtitle={source} side={conflict.theirs} accent />
        </Box>

        <Regions regions={conflict.regions} />

        <Paths conflict={conflict} owner={owner} name={name} target={target} source={source} />
      </Box>
    </Box>
  );
};

/**
 * Which parts of the file actually disagree.
 *
 * Shown only where the engine merged the file line by line and could not
 * reconcile part of it. Its absence is not "no regions" but "not established" -
 * a binary file or a directory has no lines to speak of - so nothing is
 * rendered rather than an empty list, which would read as a claim.
 *
 * The three columns line up with the three sides above, so a range can be read
 * against the version it belongs to.
 */
const Regions = ({ regions }) => {
  if (!regions?.length) return null;

  return (
    <Box sx={{ mt: 3 }}>
      <Text sx={{ display: "block", fontSize: 0, color: "fg.muted", mb: 2 }}>
        {regions.length === 1
          ? "One stretch of this file could not be reconciled"
          : `${regions.length} stretches of this file could not be reconciled`}
        . The rest of it merged.
      </Text>

      <Box sx={{ display: "flex", flexDirection: "column", gap: 2 }}>
        {regions.map((region, index) => (
          <Box
            key={`${region.base?.start}-${region.ours?.start}-${region.theirs?.start}-${index}`}
            sx={{
              display: "grid",
              gridTemplateColumns: ["1fr", "1fr", "repeat(3, minmax(0, 1fr))"],
              gap: 2,
            }}
          >
            <Range label="base" range={region.base} />
            <Range label="ours" range={region.ours} />
            <Range label="theirs" range={region.theirs} />
          </Box>
        ))}
      </Box>
    </Box>
  );
};

const Range = ({ label, range }) => (
  <Box
    sx={{
      border: "1px solid",
      borderColor: "border.default",
      borderRadius: 2,
      bg: "canvas.inset",
      px: 2,
      py: 1,
      display: "flex",
      alignItems: "baseline",
      gap: 2,
      minWidth: 0,
    }}
  >
    <Text sx={{ fontSize: 0, color: "fg.subtle", flexShrink: 0 }}>{label}</Text>
    <Text sx={{ fontFamily: "mono", fontSize: 0, overflowWrap: "anywhere", minWidth: 0 }}>
      {describeRange(range)}
    </Text>
  </Box>
);

/**
 * One side of a conflict.
 *
 * The object id and mode are shown because they are the only unambiguous way to
 * say which version is meant - two sides of a content conflict differ in
 * nothing a reader can see except their ids.
 */
const Side = ({ title, subtitle, side, accent }) => (
  <Box
    sx={{
      border: "1px solid",
      borderColor: "border.default",
      borderRadius: 2,
      bg: "canvas.inset",
      p: 2,
      minWidth: 0,
    }}
  >
    <Box sx={{ display: "flex", alignItems: "baseline", gap: 2, mb: 1, minWidth: 0 }}>
      <Text sx={{ fontSize: 0, fontWeight: 600, color: accent ? "fg.default" : "fg.muted" }}>
        {title}
      </Text>
      <Text
        sx={{
          fontSize: 0,
          color: "fg.subtle",
          fontFamily: subtitle?.includes("/") ? "mono" : "normal",
          overflow: "hidden",
          textOverflow: "ellipsis",
          whiteSpace: "nowrap",
          minWidth: 0,
        }}
        title={subtitle}
      >
        {subtitle}
      </Text>
    </Box>

    {side ? (
      <Box sx={{ display: "flex", alignItems: "center", gap: 2, minWidth: 0 }}>
        <Octicon
          icon={side.directory ? FileDirectoryFillIcon : FileIcon}
          size={12}
          sx={{ color: side.directory ? "accent.fg" : "fg.subtle", flexShrink: 0 }}
        />
        <Text sx={{ fontFamily: "mono", fontSize: 0, color: "fg.muted" }} title={side.id}>
          {side.id.slice(0, 10)}
        </Text>
        <Text sx={{ fontFamily: "mono", fontSize: 0, color: "fg.subtle" }}>{side.mode}</Text>
        {side.directory && (
          <Label sx={{ color: "accent.fg", borderColor: "accent.muted", flexShrink: 0 }}>
            directory
          </Label>
        )}
      </Box>
    ) : (
      <Box sx={{ display: "flex", alignItems: "center", gap: 2 }}>
        <Octicon icon={TrashIcon} size={12} sx={{ color: "danger.fg" }} />
        <Text sx={{ fontSize: 0, color: "danger.fg" }}>not present</Text>
      </Box>
    )}
  </Box>
);

/**
 * Where each side of the conflict can be looked at.
 *
 * A link is offered only where one honestly exists. The blob viewer addresses a
 * file by revision and path, which works for a file on a branch; it cannot
 * address a directory that one side turned into a file, and there is no merge
 * result to point at, so those cases say so rather than link somewhere wrong.
 */
const Paths = ({ conflict, owner, name, target, source }) => {
  const link = (branch, side) => {
    if (!side) return null;
    if (side.directory) return null;
    return `/${owner}/${name}/blob/${encodeURIComponent(branch)}/${conflict.path}`;
  };

  const ours = link(target, conflict.ours);
  const theirs = link(source, conflict.theirs);
  const unlinkable = [];
  if (conflict.ours?.directory) unlinkable.push(`ours is a directory on ${target}`);
  if (conflict.theirs?.directory) unlinkable.push(`theirs is a directory on ${source}`);

  return (
    <Box sx={{ mt: 3, display: "flex", gap: 3, flexWrap: "wrap", alignItems: "center" }}>
      {ours && (
        <Link as={RouterLink} to={ours} sx={{ fontSize: 0 }}>
          View on {target}
        </Link>
      )}
      {theirs && (
        <Link as={RouterLink} to={theirs} sx={{ fontSize: 0 }}>
          View on {source}
        </Link>
      )}
      <Link
        as={RouterLink}
        to={`/${owner}/${name}/compare?base=${encodeURIComponent(target)}&head=${encodeURIComponent(source)}#file-${encodeURIComponent(conflict.path)}`}
        sx={{ fontSize: 0 }}
      >
        Compare the branches
      </Link>

      {unlinkable.length > 0 && (
        <Text sx={{ fontSize: 0, color: "fg.subtle" }}>
          No file to open: {unlinkable.join(", ")}.
        </Text>
      )}
    </Box>
  );
};

export default ConflictList;
