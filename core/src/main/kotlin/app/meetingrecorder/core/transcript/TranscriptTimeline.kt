package app.meetingrecorder.core.transcript

import app.meetingrecorder.core.model.Photo

/** One entry of the merged transcript: a segment, or a photo with its 1-based number in `meeting.json`. */
public sealed interface TranscriptItem {
    public data class SegmentItem(val segment: TranscriptSegment) : TranscriptItem

    public data class PhotoItem(val photo: Photo, val number: Int) : TranscriptItem
}

/**
 * The one placement rule for photos in a transcript, used by `transcript.md` and by the screen
 * (contracts/transcript-folder.md, "Photo placement rule"). A photo with `atFrame = p` is placed
 * immediately before the first segment whose `startFrame > p`, or after the last segment when there
 * is none. Photos keep their order in `meeting.json`.
 */
public object TranscriptTimeline {

    public fun interleave(segments: List<TranscriptSegment>, photos: List<Photo>): List<TranscriptItem> {
        val items = ArrayList<TranscriptItem>(segments.size + photos.size)
        var next = 0
        for (segment in segments) {
            while (next < photos.size && photos[next].atFrame < segment.startFrame) {
                items += TranscriptItem.PhotoItem(photos[next], next + 1)
                next++
            }
            items += TranscriptItem.SegmentItem(segment)
        }
        while (next < photos.size) {
            items += TranscriptItem.PhotoItem(photos[next], next + 1)
            next++
        }
        return items
    }
}
