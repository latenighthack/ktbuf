# Isolated publication bootstrap

Fullhouse publication reproduced a failure during Kotlin JS aggregate dependency resolution: workspace eachDependency version overrides converted a source project edge into the nonexistent external coordinate com.latenighthack.ktbuf:library:<new-hash>. The library had not been published and its actual artifact ID is ktbuf-library.

Workspace pinning now preserves dependencies whose group/name identify projects in this same build; external module dependencies still use exclusive immutable manifest coordinates. The previously failing :patch:jsPackageJson now passes against a manifest whose new ktbuf artifact does not yet exist. Release resolution and source runtime bytes are unchanged. Transport/parser source remains the original reviewed 13d4640; this correction lives in a separate publication worktree.
