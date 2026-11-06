# Lil Blobs

A bowl of liquid glass you can squish. Drag a blob, throw it, hold one to pop it into
droplets. Nothing to win, nothing to lose, no permissions, ~1 MB.

## The glass

The blobs are not images. Each one is drawn every frame from four shaders:

- a radial **body** gradient lit from the top left, going pastel to saturated at the rim
- a clipped **bounce light** that pools at the bottom, like light coming through the jelly
- a **specular** highlight offset toward the light source, plus two small glints
- a soft **coloured shadow** under the blob so it floats above the paper background

Over that, `BlobView` runs real physics on a Choreographer loop: the delta is clamped
so a stalled frame can't explode the sim, blobs bounce off the walls with restitution,
collide pairwise with impulse exchange, and each impact kicks a damped spring that
squashes and recovers the scale. That spring is what makes them feel like liquid rather
than circles.

## Interactions

- **Drag** - the blob springs toward your finger, then keeps the velocity you throw it with.
- **Tap** empty space - a new blob drops in with an overshoot pop.
- **Hold** a blob - it bursts into eight droplets that arc under gravity and fade.
- **Shake** the phone - everything wobbles.

Six synthesized sounds (grab, bounce, wobble, release, pop, spawn) sit under it all, and
they are rate-limited so collisions can't machine-gun.

## Build

```
gradle assembleRelease
```

CI builds and publishes a signed APK on every push to `main`, signed with a committed
key so updates install over the previous version.
