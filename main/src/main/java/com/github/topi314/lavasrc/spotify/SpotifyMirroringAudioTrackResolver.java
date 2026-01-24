package com.github.topi314.lavasrc.spotify;

import com.github.topi314.lavasrc.applemusic.AppleMusicSourceManager;
import com.github.topi314.lavasrc.mirror.MirroringAudioSourceManager;
import com.github.topi314.lavasrc.mirror.MirroringAudioTrack;
import com.github.topi314.lavasrc.mirror.MirroringAudioTrackResolver;
import com.sedmelluq.discord.lavaplayer.track.AudioItem;
import com.sedmelluq.discord.lavaplayer.track.AudioPlaylist;
import com.sedmelluq.discord.lavaplayer.track.AudioReference;
import com.sedmelluq.discord.lavaplayer.track.AudioTrack;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Spotify-specific track resolver with advanced scoring algorithm
 * for better track matching similar to NodeLink implementation.
 */
public class SpotifyMirroringAudioTrackResolver implements MirroringAudioTrackResolver {

	private static final Logger log = LoggerFactory.getLogger(SpotifyMirroringAudioTrackResolver.class);
	private static final double DURATION_TOLERANCE = 0.05; // 5% tolerance for duration matching

	private String[] providers = {
		"ytsearch:\"" + MirroringAudioSourceManager.ISRC_PATTERN + "\"",
		"ytsearch:" + MirroringAudioSourceManager.QUERY_PATTERN
	};

	public SpotifyMirroringAudioTrackResolver(String[] providers) {
		if (providers != null && providers.length > 0) {
			this.providers = providers;
		}
	}

	@Override
	public AudioItem apply(MirroringAudioTrack mirroringAudioTrack) {
		for (var provider : providers) {
			if (provider.startsWith(SpotifySourceManager.SEARCH_PREFIX)) {
				log.warn("Can not use spotify search as search provider!");
				continue;
			}

			if (provider.startsWith(AppleMusicSourceManager.SEARCH_PREFIX)) {
				log.warn("Can not use apple music search as search provider!");
				continue;
			}

			if (provider.contains(MirroringAudioSourceManager.ISRC_PATTERN)) {
				if (mirroringAudioTrack.getInfo().isrc != null && !mirroringAudioTrack.getInfo().isrc.isEmpty()) {
					provider = provider.replace(MirroringAudioSourceManager.ISRC_PATTERN, mirroringAudioTrack.getInfo().isrc.replace("-", ""));
				} else {
					log.debug("Ignoring identifier \"{}\" because this track does not have an ISRC!", provider);
					continue;
				}
			}

			provider = provider.replace(MirroringAudioSourceManager.QUERY_PATTERN, getTrackTitle(mirroringAudioTrack));

			AudioItem item;
			try {
				item = mirroringAudioTrack.loadItem(provider);
			} catch (Exception e) {
				log.error("Failed to load track from provider \"{}\"!", provider, e);
				continue;
			}
			
			// If the track is an empty playlist, skip the provider
			if (item == AudioReference.NO_TRACK || (item instanceof AudioPlaylist && ((AudioPlaylist) item).getTracks().isEmpty())) {
				continue;
			}
			
			// Find best match using scoring algorithm
			if (item instanceof AudioPlaylist) {
				var tracks = ((AudioPlaylist) item).getTracks();
				var bestMatch = findBestMatch(tracks, mirroringAudioTrack, false);
				if (bestMatch != null) {
					return bestMatch;
				}
			} else {
				return item;
			}
		}

		// Retry with "official video" if no match found
		log.debug("No match found, retrying with 'official video' query for Spotify track: {}", mirroringAudioTrack.getInfo().title);
		var officialVideoQuery = getTrackTitle(mirroringAudioTrack) + " official video";
		AudioItem retryItem;
		try {
			retryItem = mirroringAudioTrack.loadItem("ytsearch:" + officialVideoQuery);
		} catch (Exception e) {
			log.error("Failed to load track with official video query!", e);
			return AudioReference.NO_TRACK;
		}
		
		if (retryItem instanceof AudioPlaylist) {
			var tracks = ((AudioPlaylist) retryItem).getTracks();
			
			// Try to find best match with scoring
			var bestMatch = findBestMatch(tracks, mirroringAudioTrack, true);
			if (bestMatch != null) {
				return bestMatch;
			}
			
			// If no match found even after scoring, return first track as fallback
			// if (!tracks.isEmpty()) {
			// 	log.debug("No scored match found, returning first track as fallback: {}", tracks.get(0).getInfo().title);
			// 	return tracks.get(0);
			// }
		}

		return AudioReference.NO_TRACK;
	}

	/**
	 * Find the best matching track from a list of candidates using a scoring algorithm
	 * similar to NodeLink's implementation.
	 * 
	 * @param candidates List of candidate tracks to score
	 * @param original Original Spotify track to match against
	 * @param isRetry Whether this is a retry attempt
	 * @return Best matching track or null if no suitable match found
	 */
	private AudioTrack findBestMatch(List<AudioTrack> candidates, MirroringAudioTrack original, boolean isRetry) {
		if (candidates.isEmpty()) {
			return null;
		}

		long targetDuration = original.getInfo().length;
		long allowedDurationDiff = (long) (targetDuration * DURATION_TOLERANCE);
		
		String normalizedOriginalTitle = normalize(original.getInfo().title);
		String normalizedOriginalAuthor = normalize(original.getInfo().author);
		
		// Parse explicit flag from URI if available
		boolean isExplicit = false;
		if (original.getInfo().uri != null && original.getInfo().uri.contains("explicit=true")) {
			isExplicit = true;
		}

		List<ScoredTrack> scoredCandidates = new ArrayList<>();
		
		for (AudioTrack candidate : candidates) {
			// Duration filter - skip tracks outside tolerance
			long durationDiff = Math.abs(candidate.getInfo().length - targetDuration);
			if (durationDiff > allowedDurationDiff) {
				continue;
			}
			
			double score = calculateScore(candidate, normalizedOriginalTitle, normalizedOriginalAuthor, isExplicit);
			
			if (score >= 0) {
				scoredCandidates.add(new ScoredTrack(candidate, score));
			}
		}

		if (scoredCandidates.isEmpty()) {
			return null;
		}

		// Sort by score descending
		scoredCandidates.sort(Comparator.comparingDouble(ScoredTrack::getScore).reversed());
		
		AudioTrack bestMatch = scoredCandidates.get(0).getTrack();
		log.debug("Selected best match for '{}': {} by {} (score: {}, explicit: {})", 
			original.getInfo().title,
			bestMatch.getInfo().title, 
			bestMatch.getInfo().author, 
			scoredCandidates.get(0).getScore(),
			isExplicit);
		
		return bestMatch;
	}

	/**
	 * Calculate matching score for a candidate track based on multiple factors:
	 * - Title word matching (100 points per word)
	 * - Artist matching (100 points per artist)
	 * - Artist similarity (50 points max using Levenshtein)
	 * - Extra words penalty (-5 points per extra word)
	 * - Clean/radio version handling (±200 points based on explicit flag)
	 * 
	 * @param candidate Track to score
	 * @param normalizedOriginalTitle Normalized original title
	 * @param normalizedOriginalAuthor Normalized original author
	 * @param isExplicit Whether the original track is explicit
	 * @return Score for this candidate (higher is better)
	 */
	private double calculateScore(AudioTrack candidate, String normalizedOriginalTitle, 
	                               String normalizedOriginalAuthor, boolean isExplicit) {
		double score = 0;
		
		String normalizedCandidateTitle = normalize(candidate.getInfo().title);
		String normalizedCandidateAuthor = normalize(candidate.getInfo().author);
		
		// Title word matching (high weight - 100 points per matching word)
		Set<String> originalTitleWords = Arrays.stream(normalizedOriginalTitle.split("\\s+"))
			.filter(w -> w.length() > 0)
			.collect(Collectors.toSet());
		Set<String> candidateTitleWords = Arrays.stream(normalizedCandidateTitle.split("\\s+"))
			.filter(w -> w.length() > 0)
			.collect(Collectors.toSet());
		
		long titleMatches = originalTitleWords.stream()
			.filter(candidateTitleWords::contains)
			.count();
		score += titleMatches * 100;
		
		// Author/Artist matching (high weight - 100 points per artist)
		String[] originalArtists = normalizedOriginalAuthor.split("[,&]");
		double authorMatchScore = 0;
		for (String artist : originalArtists) {
			String trimmedArtist = artist.trim();
			if (!trimmedArtist.isEmpty() && normalizedCandidateAuthor.contains(trimmedArtist)) {
				authorMatchScore += 100;
			}
		}
		
		if (authorMatchScore > 0) {
			score += authorMatchScore;
		} else {
			// Calculate similarity if no exact match (50 points max)
			double similarity = calculateSimilarity(normalizedOriginalAuthor, normalizedCandidateAuthor);
			score += similarity * 50;
		}
		
		// Penalty for extra words in candidate title (reduce noise from remixes, etc.)
		long extraWords = candidateTitleWords.stream()
			.filter(w -> !originalTitleWords.contains(w))
			.count();
		score -= extraWords * 5;
		
		// Handle clean/radio versions based on explicit flag
		boolean isCleanOrRadio = normalizedCandidateTitle.contains("clean") || 
		                        normalizedCandidateTitle.contains("radio");
		
		if (isExplicit) {
			// If original is explicit, strongly avoid clean versions
			if (isCleanOrRadio) {
				score -= 200;
			}
		} else {
			// If original is not explicit, slightly prefer clean versions
			if (isCleanOrRadio) {
				score += 50;
			}
		}
		
		return score;
	}

	/**
	 * Normalize string for comparison by:
	 * - Converting to lowercase
	 * - Removing featured artist notations (feat., ft.)
	 * - Removing special characters
	 * - Trimming whitespace
	 * 
	 * @param str String to normalize
	 * @return Normalized string
	 */
	private String normalize(String str) {
		if (str == null) {
			return "";
		}
		return str.toLowerCase()
			.replaceAll("feat\\.?", "")
			.replaceAll("ft\\.?", "")
			.replaceAll("[^\\w\\s]", "")
			.trim();
	}

	/**
	 * Calculate string similarity using Levenshtein distance algorithm.
	 * Returns a value between 0.0 (completely different) and 1.0 (identical).
	 * 
	 * @param str1 First string
	 * @param str2 Second string
	 * @return Similarity score (0.0 to 1.0)
	 */
	private double calculateSimilarity(String str1, String str2) {
		if (str1 == null || str2 == null) {
			return 0.0;
		}
		
		String longer = str1.length() > str2.length() ? str1 : str2;
		String shorter = str1.length() > str2.length() ? str2 : str1;
		
		if (longer.length() == 0) {
			return 1.0;
		}
		
		int editDistance = levenshteinDistance(longer, shorter);
		return (longer.length() - editDistance) / (double) longer.length();
	}

	/**
	 * Calculate Levenshtein distance (edit distance) between two strings.
	 * This represents the minimum number of single-character edits (insertions, 
	 * deletions, or substitutions) required to change one string into the other.
	 * 
	 * @param str1 First string
	 * @param str2 Second string
	 * @return Edit distance
	 */
	private int levenshteinDistance(String str1, String str2) {
		int[][] matrix = new int[str2.length() + 1][str1.length() + 1];
		
		for (int i = 0; i <= str2.length(); i++) {
			matrix[i][0] = i;
		}
		
		for (int j = 0; j <= str1.length(); j++) {
			matrix[0][j] = j;
		}
		
		for (int i = 1; i <= str2.length(); i++) {
			for (int j = 1; j <= str1.length(); j++) {
				if (str2.charAt(i - 1) == str1.charAt(j - 1)) {
					matrix[i][j] = matrix[i - 1][j - 1];
				} else {
					matrix[i][j] = Math.min(
						Math.min(matrix[i - 1][j - 1] + 1, matrix[i][j - 1] + 1),
						matrix[i - 1][j] + 1
					);
				}
			}
		}
		
		return matrix[str2.length()][str1.length()];
	}

	/**
	 * Generate search query from track information.
	 * 
	 * @param mirroringAudioTrack Track to generate query for
	 * @return Search query string
	 */
	public String getTrackTitle(MirroringAudioTrack mirroringAudioTrack) {
		var query = mirroringAudioTrack.getInfo().title;
		if (!mirroringAudioTrack.getInfo().author.equals("unknown")) {
			query += " " + mirroringAudioTrack.getInfo().author;
		}
		return query;
	}

	/**
	 * Helper class to store a track with its calculated score.
	 */
	private static class ScoredTrack {
		private final AudioTrack track;
		private final double score;

		public ScoredTrack(AudioTrack track, double score) {
			this.track = track;
			this.score = score;
		}

		public AudioTrack getTrack() {
			return track;
		}

		public double getScore() {
			return score;
		}
	}

}
