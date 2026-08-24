package com.mopicmp.npcstudio.client.entity;

/**
 * A gesture carried on a vanilla render state.
 *
 * The state is Minecraft's class and cannot grow a field of ours, so a mixin
 * adds one and this interface is how the rest of the code reaches it. It is the
 * ordinary way to attach data to something you do not own — the alternative,
 * a map keyed by the state object, would leak every entity that ever rendered.
 */
public interface GestureHolder {

	String npcStudio$gesture();

	float npcStudio$gestureAge();

	/**
	 * How much of the gesture to show, nought to one.
	 *
	 * Below one only while a gesture with a set length is finishing. Emotes end
	 * wherever the animator left them — a bow ends bowed — so a gesture cut off
	 * at its last tick would snap the body upright in one frame.
	 */
	float npcStudio$gestureStrength();

	void npcStudio$setGesture(String name, float age, float strength);

	/**
	 * The animation being left behind, while it is still being left behind.
	 *
	 * <h2>Why an outgoing animation has to be carried too</h2>
	 *
	 * Because a change of animation has no length of its own and needs one. It was
	 * reported about a character standing and smoking who notices you: the problem
	 * was not that the change was quick, it was that there was no change at all —
	 * one frame of one pose, then one frame of another, with nothing in between.
	 *
	 * Both animations have to be in hand at once to fade between them, and the model
	 * is handed a render state and nothing else. So the outgoing one rides along
	 * here until it has finished going.
	 */
	String npcStudio$leaving();

	float npcStudio$leavingAge();

	/** How far into the change, nought at the moment it starts and one at the end. */
	float npcStudio$changing();

	void npcStudio$setLeaving(String name, float age, float changing);

	/**
	 * What this character's eyes are doing this frame: where they are, how shut,
	 * which way turned.
	 *
	 * Carried rather than assumed. A lid belongs over the eyes that are there, and
	 * on a face without any it belongs nowhere at all.
	 */
	com.mopicmp.npcstudio.client.skin.Eyes npcStudio$eyes();

	void npcStudio$setEyes(com.mopicmp.npcstudio.client.skin.Eyes eyes);

	/** How this character is built, or the ordinary build for anybody else. */
	com.mopicmp.npcstudio.entity.BodyShape npcStudio$shape();

	void npcStudio$setShape(com.mopicmp.npcstudio.entity.BodyShape shape);

	/**
	 * What the scene says this character is doing, or null when it is in none.
	 *
	 * Carried across rather than looked up where it is needed, because where it is
	 * needed is inside the model posing itself, and by then the entity is gone —
	 * the model is handed a render state and nothing else. The whole sample rather
	 * than a list of numbers, so that each part can ask for its own channels and
	 * hand over its own fallback; a scene has to be able to say nothing about a
	 * bone and leave it doing whatever it was doing.
	 */
	com.mopicmp.npcstudio.client.scene.Playing.Sample npcStudio$staged();

	void npcStudio$setStaged(com.mopicmp.npcstudio.client.scene.Playing.Sample staged);

}
